# Demo script

Five scenarios. Each one exercises a different part of the design, and each is triggered by
ordinary API input - no container killing, no timing tricks.

Set a base URL first.

```powershell
$order = "http://localhost:8081"
$inv   = "http://localhost:8082"
$pay   = "http://localhost:8083"
$note  = "http://localhost:8084"
```

Helper for reading an order back:

```powershell
function Get-Order($id) { Invoke-RestMethod "$order/api/orders/$id" }
```

---

## 1. Happy path

```powershell
$body = @{
  customerId = "CUST-1001"
  items = @(@{ productId = "P-LAPTOP"; quantity = 1; unitPrice = 1200.00 })
} | ConvertTo-Json -Depth 5

$created = Invoke-RestMethod -Method Post -Uri "$order/api/orders" -ContentType 'application/json' -Body $body
$id = $created.orderId
Start-Sleep -Seconds 3
Get-Order $id | Select-Object status, sagaState, totalAmount
```

Expect `CONFIRMED` / `COMPLETED`.

What happened: the order and its saga were written in one transaction alongside two outbox rows.
The poller published them. `inventory-service` reserved stock and replied; the orchestrator sent
the payment command; `payment-service` authorised and replied; the orchestrator confirmed the order
and published `ORDER_CONFIRMED`; `notification-service` rendered two emails.

Check the side effects in the other services:

```powershell
Invoke-RestMethod "$inv/api/inventory/P-LAPTOP"
Invoke-RestMethod "$pay/api/payments/order/$id"
Invoke-RestMethod "$note/api/notifications/order/$id" | Select-Object eventType, subject
```

---

## 2. Business rejection, nothing to compensate

`P-OUT-OF-STOCK` is seeded with zero stock.

```powershell
$body = @{
  customerId = "CUST-1002"
  items = @(@{ productId = "P-OUT-OF-STOCK"; quantity = 1; unitPrice = 50.00 })
} | ConvertTo-Json -Depth 5

$id = (Invoke-RestMethod -Method Post -Uri "$order/api/orders" -ContentType 'application/json' -Body $body).orderId
Start-Sleep -Seconds 3
Get-Order $id | Select-Object status, sagaState, failureReason
```

Expect `CANCELLED` / `FAILED`, reason `Insufficient stock for P-OUT-OF-STOCK...`.

The point: a rejection is a *successful* handling of the message. `inventory-service` replies
`FAILED` rather than throwing, so nothing is retried and nothing is dead-lettered. The saga ends in
`FAILED` rather than `COMPENSATED` because no step had committed anything yet.

---

## 3. Compensation - payment declined after stock was reserved

Any order over 10,000 is declined (`oms.payment.auto-decline-above`).

```powershell
$body = @{
  customerId = "CUST-1003"
  items = @(@{ productId = "P-MONITOR"; quantity = 40; unitPrice = 400.00 })
} | ConvertTo-Json -Depth 5

Invoke-RestMethod "$inv/api/inventory/P-MONITOR"   # note reservedQty before

$id = (Invoke-RestMethod -Method Post -Uri "$order/api/orders" -ContentType 'application/json' -Body $body).orderId
Start-Sleep -Seconds 4
Get-Order $id | Select-Object status, sagaState, failureReason
Invoke-RestMethod "$inv/api/inventory/P-MONITOR"   # reservedQty back to where it started
Invoke-RestMethod "$inv/api/inventory/reservations/$id"
```

Expect `CANCELLED` / `COMPENSATED`, and the reservation in status `RELEASED`.

The point: stock was really taken and really given back. Watch `order-service`'s log go
`AWAITING_PAYMENT` -> `COMPENSATING_INVENTORY` -> `COMPENSATED`.

---

## 4. The full compensation chain, in reverse

`simulateConfirmFailure` makes the saga's final local step fail *after* payment succeeded, so both
compensations have to run - and in the right order.

```powershell
$body = @{
  customerId = "CUST-1004"
  simulateConfirmFailure = $true
  items = @(@{ productId = "P-HEADPHONES"; quantity = 2; unitPrice = 90.00 })
} | ConvertTo-Json -Depth 5

$id = (Invoke-RestMethod -Method Post -Uri "$order/api/orders" -ContentType 'application/json' -Body $body).orderId
Start-Sleep -Seconds 5
Get-Order $id | Select-Object status, sagaState, failureReason
Invoke-RestMethod "$pay/api/payments/order/$id" | Select-Object status, amount
```

Expect `CANCELLED` / `COMPENSATED` and the payment in `REFUNDED`.

The point: compensation unwinds the most recent commitment first - refund, *then* release. Reverse
order is not cosmetic. Releasing stock before refunding would leave a window in which the customer
has been charged for something already sold to somebody else.

---

## 5. Retries and the dead-letter topic

`P-BOOM` makes `inventory-service` throw a retryable exception every single time.

```powershell
$body = @{
  customerId = "CUST-1005"
  items = @(@{ productId = "P-BOOM"; quantity = 1; unitPrice = 10.00 })
} | ConvertTo-Json -Depth 5

$id = (Invoke-RestMethod -Method Post -Uri "$order/api/orders" -ContentType 'application/json' -Body $body).orderId
```

Watch `inventory-service`'s log: three attempts, roughly 1s and 2s apart, then the record is routed
to `order.commands.reserve-inventory.DLT`.

```powershell
Start-Sleep -Seconds 10
Invoke-RestMethod "$order/api/ops/dlt"
Invoke-RestMethod "$order/api/ops/dlt/order.commands.reserve-inventory" |
    Select-Object offset, exceptionMessage
```

`order.commands.reserve-inventory.DLT` shows `total = 1`, `pendingReplay = 1`.

The order is now stuck in `AWAITING_INVENTORY` - correctly. No reply ever came, so the state machine
has nothing to act on. After two minutes `StuckSagaMonitor` starts logging `STUCK SAGA`.

### Replay

The real dead-letter workflow is two steps: **remove the cause, then replay.** Replaying first just
fills the DLT again, which you can watch happen if you skip step one.

```powershell
# Step 1 - remove the cause. Clearing the trigger disables the simulator.
Invoke-RestMethod -Method Put -Uri "$inv/api/inventory/simulation" `
    -ContentType 'application/json' -Body '{"failingProductId":""}'

# Step 2 - replay.
Invoke-RestMethod -Method Post -Uri "$order/api/ops/dlt/order.commands.reserve-inventory/replay"

Start-Sleep -Seconds 8
Get-Order $id | Select-Object status, sagaState
Invoke-RestMethod "$order/api/ops/dlt"
```

The stuck order reaches `CONFIRMED` / `COMPLETED`, and `pendingReplay` drops to `0` while `total`
stays at `1` - Kafka is a log, so replaying a record does not remove it. Replaying a second time
reports `replayed = 0`, `Nothing left to replay`.

Put the simulator back when you are done:

```powershell
Invoke-RestMethod -Method Put -Uri "$inv/api/inventory/simulation" `
    -ContentType 'application/json' -Body '{"failingProductId":"P-BOOM"}'
```

The point: a dead-letter topic is only useful if you can drain it. Replay is safe here because
every consumer is idempotent - if the message had partially applied before failing, replaying it
would not double-apply.

---

## 6. Idempotency, shown directly

The cleanest demonstration is the test, not curl - a duplicate has to arrive on the *broker* to be
interesting, and the API will not produce one for you:

```bash
mvn -pl order-service -am verify
```

`duplicateReplyIsIgnored` sends the identical `SagaReply` - same `messageId` - twice, and asserts
that exactly one payment command is emitted. Delete the `firstTimeSeeing` check in
`OrderSagaOrchestrator` and that test fails with two payment commands: the customer charged twice.

You can also watch it happen live. `notification-service` logs
`Skipping duplicate message ... (group notification-service)` whenever the outbox republishes an
event after a failed offset commit.

To inspect the ledger:

```bash
docker exec -it oms-postgres psql -U inventory_user -d inventorydb \
  -c "select message_id, consumer_group, topic from processed_message order by processed_at desc limit 10;"
```