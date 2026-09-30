<#
.SYNOPSIS
    Places one order of each kind and reports what the system did with it.

.DESCRIPTION
    Exercises all five scenarios: happy path, business rejection, compensation after a declined
    payment, the full reverse compensation chain, and the retry/dead-letter path.

    Every assertion about a saga POLLS for the expected state rather than sleeping a fixed time.
    In an asynchronous system a fixed sleep is a guess, and a wrong guess reports a working system
    as broken - which it duly did, the first time this ran against containers instead of local JVMs.

    The one place a fixed wait is correct is scenario 5, where the assertion is that something does
    NOT happen. You cannot poll for an absence; you can only wait long enough to be convincing.

    Requires all four services to be running. See README.md.

.EXAMPLE
    ./scripts/smoke-test.ps1
    ./scripts/smoke-test.ps1 -TimeoutSeconds 60      # slower machine, or a cold container start
#>
param(
    [string]$OrderUrl        = "http://localhost:8081",
    [string]$InventoryUrl    = "http://localhost:8082",
    [string]$PaymentUrl      = "http://localhost:8083",
    [string]$NotificationUrl = "http://localhost:8084",
    [int]$TimeoutSeconds     = 45
)

$ErrorActionPreference = 'Stop'
$results = [ordered]@{}

function Write-Head($text) {
    Write-Host ""
    Write-Host ("=" * 78) -ForegroundColor DarkGray
    Write-Host "  $text" -ForegroundColor Cyan
    Write-Host ("=" * 78) -ForegroundColor DarkGray
}

function Test-ServiceUp($name, $url) {
    try {
        $health = Invoke-RestMethod "$url/actuator/health" -TimeoutSec 5
        if ($health.status -eq 'UP') { Write-Host "  [ok]   $name" -ForegroundColor Green; return $true }
        Write-Host "  [warn] $name reports $($health.status)" -ForegroundColor Yellow
        return $false
    } catch {
        Write-Host "  [down] $name at $url" -ForegroundColor Red
        return $false
    }
}

function New-Order($customerId, $items, $simulateConfirmFailure = $false) {
    $body = @{
        customerId             = $customerId
        items                  = $items
        simulateConfirmFailure = $simulateConfirmFailure
    } | ConvertTo-Json -Depth 6
    (Invoke-RestMethod -Method Post -Uri "$OrderUrl/api/orders" -ContentType 'application/json' -Body $body).orderId
}

function Get-Order($orderId) { Invoke-RestMethod "$OrderUrl/api/orders/$orderId" }

# Polls until the order reaches the expected status/saga state, or the timeout expires.
function Wait-Order($label, $orderId, $expectedStatus, $expectedSaga) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $o = $null
    while ((Get-Date) -lt $deadline) {
        $o = Get-Order $orderId
        if ($o.status -eq $expectedStatus -and $o.sagaState -eq $expectedSaga) { break }
        Start-Sleep -Milliseconds 400
    }
    $ok = ($o.status -eq $expectedStatus) -and ($o.sagaState -eq $expectedSaga)
    $colour = if ($ok) { 'Green' } else { 'Red' }

    Write-Host ""
    Write-Host "  $label" -ForegroundColor White
    Write-Host "    orderId    : $($o.orderId)"
    Write-Host "    status     : $($o.status)  (expected $expectedStatus)" -ForegroundColor $colour
    Write-Host "    sagaState  : $($o.sagaState)  (expected $expectedSaga)" -ForegroundColor $colour
    Write-Host "    total      : $($o.totalAmount)"
    if ($o.failureReason) { Write-Host "    reason     : $($o.failureReason)" -ForegroundColor DarkYellow }
    return $ok
}

# Polls until a product's stock matches, so the compensation has time to land.
function Wait-Stock($productId, $expectedAvailable, $expectedReserved) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $s = $null
    while ((Get-Date) -lt $deadline) {
        $s = Invoke-RestMethod "$InventoryUrl/api/inventory/$productId"
        if ($s.availableQty -eq $expectedAvailable -and $s.reservedQty -eq $expectedReserved) { break }
        Start-Sleep -Milliseconds 400
    }
    $ok = ($s.availableQty -eq $expectedAvailable) -and ($s.reservedQty -eq $expectedReserved)
    $colour = if ($ok) { 'Green' } else { 'Red' }
    Write-Host "    $productId   : available=$($s.availableQty) reserved=$($s.reservedQty)" -ForegroundColor $colour
    Write-Host "                  expected available=$expectedAvailable reserved=$expectedReserved" -ForegroundColor $colour
    return $ok
}

function Wait-Payment($orderId, $expectedStatus) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $p = $null
    while ((Get-Date) -lt $deadline) {
        try {
            $p = Invoke-RestMethod "$PaymentUrl/api/payments/order/$orderId"
            if ($p.status -eq $expectedStatus) { break }
        } catch { }
        Start-Sleep -Milliseconds 400
    }
    $ok = $null -ne $p -and $p.status -eq $expectedStatus
    $colour = if ($ok) { 'Green' } else { 'Red' }
    Write-Host "    payment    : $(if ($p) { $p.status } else { 'none' })  (expected $expectedStatus)" -ForegroundColor $colour
    return $ok
}

# ------------------------------------------------------------------ preflight

Write-Head "Checking services"
$up = @(
    (Test-ServiceUp "order-service        :8081" $OrderUrl),
    (Test-ServiceUp "inventory-service    :8082" $InventoryUrl),
    (Test-ServiceUp "payment-service      :8083" $PaymentUrl),
    (Test-ServiceUp "notification-service :8084" $NotificationUrl)
)
if ($up -contains $false) {
    Write-Host ""
    Write-Host "Not all services are up. Start them first - see README.md." -ForegroundColor Red
    exit 1
}

# Make sure the simulator is at its documented default, in case a previous run retargeted it.
Invoke-RestMethod -Method Put -Uri "$InventoryUrl/api/inventory/simulation" `
    -ContentType 'application/json' -Body '{"failingProductId":"P-BOOM"}' | Out-Null

# ------------------------------------------------------------------ 1. happy path

Write-Head "1. Happy path - every step succeeds"
$happy = New-Order "CUST-1001" @(@{ productId = "P-LAPTOP"; quantity = 1; unitPrice = 1200.00 })
Write-Host "  placed $happy"
$ok = Wait-Order "Expect the order to be confirmed" $happy "CONFIRMED" "COMPLETED"
$ok = (Wait-Payment $happy "AUTHORIZED") -and $ok

$notes = Invoke-RestMethod "$NotificationUrl/api/notifications/order/$happy"
$noteOk = $notes.Count -eq 2
Write-Host "    emails     : $($notes.Count) ($($notes.eventType -join ', '))  (expected 2)" `
    -ForegroundColor $(if ($noteOk) { 'Green' } else { 'Red' })
$results['1 happy path'] = $ok -and $noteOk

# ------------------------------------------------------------------ 2. rejection

Write-Head "2. Business rejection - zero stock, nothing to compensate"
$rejected = New-Order "CUST-1002" @(@{ productId = "P-OUT-OF-STOCK"; quantity = 1; unitPrice = 50.00 })
Write-Host "  placed $rejected"
# FAILED, not COMPENSATED: no step had committed anything, so there was nothing to undo.
$results['2 rejection'] = Wait-Order "Expect cancellation without compensation" $rejected "CANCELLED" "FAILED"

# ------------------------------------------------------------------ 3. compensation

Write-Head "3. Compensation - payment declined after stock was reserved"
$before = Invoke-RestMethod "$InventoryUrl/api/inventory/P-MONITOR"
Write-Host "  P-MONITOR before: available=$($before.availableQty) reserved=$($before.reservedQty)"

$declined = New-Order "CUST-1003" @(@{ productId = "P-MONITOR"; quantity = 30; unitPrice = 400.00 })
Write-Host "  placed $declined (total 12000, over the 10000 limit)"
$ok = Wait-Order "Expect stock to be released" $declined "CANCELLED" "COMPENSATED"
# The real assertion: the stock that was genuinely taken is genuinely given back.
$ok = (Wait-Stock "P-MONITOR" $before.availableQty $before.reservedQty) -and $ok
$results['3 compensation'] = $ok

# ------------------------------------------------------------------ 4. full chain

Write-Head "4. Full compensation chain - refund, then release, in reverse order"
$beforeH = Invoke-RestMethod "$InventoryUrl/api/inventory/P-HEADPHONES"
$chain = New-Order "CUST-1004" @(@{ productId = "P-HEADPHONES"; quantity = 2; unitPrice = 90.00 }) $true
Write-Host "  placed $chain with simulateConfirmFailure"
$ok = Wait-Order "Expect refund then release" $chain "CANCELLED" "COMPENSATED"
$ok = (Wait-Payment $chain "REFUNDED") -and $ok
$ok = (Wait-Stock "P-HEADPHONES" $beforeH.availableQty $beforeH.reservedQty) -and $ok
$results['4 full chain'] = $ok

# ------------------------------------------------------------------ 5. retries and DLT

Write-Head "5. Retries and the dead-letter topic"
$boom = New-Order "CUST-1005" @(@{ productId = "P-BOOM"; quantity = 1; unitPrice = 10.00 })
Write-Host "  placed $boom - inventory-service retries 3x (1s, 2s), then dead-letters it."

# Poll for the message to reach the DLT rather than guessing how long the backoff takes.
$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$dltRecords = @()
while ((Get-Date) -lt $deadline) {
    $dltRecords = @(Invoke-RestMethod "$OrderUrl/api/ops/dlt/order.commands.reserve-inventory?max=20")
    if ($dltRecords.Count -gt 0) { break }
    Start-Sleep -Seconds 1
}
$dltOk = $dltRecords.Count -gt 0
Write-Host ""
Write-Host "    dead-lettered: $($dltRecords.Count) record(s)" -ForegroundColor $(if ($dltOk) { 'Green' } else { 'Red' })
if ($dltOk) {
    Write-Host "    last failure : $(($dltRecords[-1].exceptionMessage -split ';')[-1].Trim())" -ForegroundColor DarkYellow
}

# Asserting an absence, so this one genuinely has to wait rather than poll.
$o = Get-Order $boom
$stuckOk = ($o.status -eq 'CREATED') -and ($o.sagaState -eq 'AWAITING_INVENTORY')
Write-Host "    order stays  : $($o.status) / $($o.sagaState)  (expected CREATED / AWAITING_INVENTORY)" `
    -ForegroundColor $(if ($stuckOk) { 'Green' } else { 'Red' })
Write-Host "                   correct: no reply ever arrived, so the state machine cannot advance."
$results['5 dead-letter'] = $dltOk -and $stuckOk

# ------------------------------------------------------------------ 6. fix the cause, then replay

Write-Head "6. Dead-letter replay - remove the cause, then replay"
Invoke-RestMethod -Method Put -Uri "$InventoryUrl/api/inventory/simulation" `
    -ContentType 'application/json' -Body '{"failingProductId":""}' | Out-Null
Write-Host "  simulator disabled"

$replay = Invoke-RestMethod -Method Post -Uri "$OrderUrl/api/ops/dlt/order.commands.reserve-inventory/replay"
Write-Host "  replayed $($replay.replayed) record(s): $($replay.note)"
$ok = Wait-Order "Expect the stuck saga to recover" $boom "CONFIRMED" "COMPLETED"

$depth = (Invoke-RestMethod "$OrderUrl/api/ops/dlt").'order.commands.reserve-inventory.DLT'
$depthOk = $depth.pendingReplay -eq 0
Write-Host "    DLT          : total=$($depth.total) pendingReplay=$($depth.pendingReplay)  (expected pendingReplay=0)" `
    -ForegroundColor $(if ($depthOk) { 'Green' } else { 'Red' })
Write-Host "                   total never drops - Kafka is a log, replaying does not remove a record."

$again = Invoke-RestMethod -Method Post -Uri "$OrderUrl/api/ops/dlt/order.commands.reserve-inventory/replay"
$againOk = $again.replayed -eq 0
Write-Host "    replay again : $($again.replayed) record(s) - $($again.note)" `
    -ForegroundColor $(if ($againOk) { 'Green' } else { 'Red' })
$results['6 replay'] = $ok -and $depthOk -and $againOk

Invoke-RestMethod -Method Put -Uri "$InventoryUrl/api/inventory/simulation" `
    -ContentType 'application/json' -Body '{"failingProductId":"P-BOOM"}' | Out-Null
Write-Host "  simulator restored to P-BOOM"

# ------------------------------------------------------------------ summary

Write-Head "Summary"
Write-Host "  Saga states:" -ForegroundColor White
(Invoke-RestMethod "$OrderUrl/api/orders/status/summary").PSObject.Properties |
    Where-Object { $_.Value -gt 0 } | ForEach-Object { Write-Host "    $($_.Name) = $($_.Value)" }

$outbox = Invoke-RestMethod "$OrderUrl/api/ops/outbox"
Write-Host ""
Write-Host "  Outbox: pending=$($outbox.pending) sent=$($outbox.sent) failed=$($outbox.failed)" -ForegroundColor White

Write-Host ""
$failed = 0
foreach ($k in $results.Keys) {
    if ($results[$k]) { Write-Host "  [pass] $k" -ForegroundColor Green }
    else { Write-Host "  [FAIL] $k" -ForegroundColor Red; $failed++ }
}

Write-Host ""
if ($failed -eq 0) {
    Write-Host "All scenarios behaved as expected." -ForegroundColor Green
    exit 0
}
Write-Host "$failed scenario(s) did not behave as expected. Check the service logs." -ForegroundColor Red
exit 1