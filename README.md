# Event-Driven Order Management System

Four Spring Boot microservices, four isolated PostgreSQL databases, and Kafka between them.
Placing an order spans three services and cannot be done in a single database transaction, so the
project is really about the patterns that make that safe:

| Pattern | Where to look |
| --- | --- |
| **Saga (orchestration)** | `order-service` -> `saga/OrderSagaOrchestrator.java` |
| **Transactional outbox** | `common-messaging` -> `OutboxRecorder`, `OutboxPublisher` |
| **Idempotent consumers** | `common-messaging` -> `IdempotencyGuard`, table `processed_message` |
| **Retries + dead-letter topics** | `common-messaging` -> `config/KafkaErrorHandlingConfig.java` |
| **DLT inspection and replay** | `order-service` -> `ops/DltAdminService.java` |
| **Saga timeouts** | `order-service` -> `saga/StuckSagaMonitor.java` |

`docs/DESIGN.md` explains why each decision went the way it did, including the trade-offs that
were rejected. `docs/DEMO.md` walks through the five scenarios worth showing.

---

## Architecture

```
                            POST /api/orders
                                  |
                                  v
          +---------------------------------------------+
          |              order-service :8081            |
          |   REST API  +  saga orchestrator  +  outbox  |
          +---------------------------------------------+
             |                 |                    ^
   commands  |                 | commands           | replies
             v                 v                    |
  +---------------------+  +--------------------+    |
  | inventory-service   |  | payment-service    |----+
  |       :8082         |  |      :8083         |
  +---------------------+  +--------------------+
             |                      |
             v                      v
      inventorydb              paymentdb

          order.events (fan-out, nobody blocks on it)
                          |
                          v
              +------------------------+
              | notification-service   |
              |        :8084           |
              +------------------------+
                          |
                          v
                   notificationdb
```

Three kinds of topic, and the distinction matters:

- `order.commands.*` - a directed instruction to one participant. Exactly one consumer group acts.
- `order.replies` - participants report outcomes back to the orchestrator.
- `order.events` - facts, broadcast. Anyone may subscribe; nobody is obliged to.

Every message is keyed by `orderId`, so all messages for one order land on the same partition and
stay in order relative to each other.

## The saga

`order-service` owns an explicit state machine and drives the other services:

```
  AWAITING_INVENTORY --ok--> AWAITING_PAYMENT --ok--> [local confirm] --> COMPLETED
        |                          |                        |
      fail                       fail                      fail
        |                          |                        |
        v                          v                        v
      FAILED          COMPENSATING_INVENTORY <---- COMPENSATING_PAYMENT
                                   |
                                   v
                              COMPENSATED
```

Compensation runs in reverse order of the forward steps - refund before release - because the most
recent commitment has to be undone first.

There is no distributed transaction anywhere. Each step commits locally and immediately; if a later
step fails, earlier steps are undone by *new* business operations, not by a rollback. A refund is
not the absence of a payment, and the order's history says so.

---

## Running it

### Prerequisites

- JDK 17 or newer (`java -version`)
- Maven 3.9+ (`mvn -v`)
- Docker Desktop with Compose v2, running (`docker compose version`)
- About 4 GB of free RAM

### Option A - everything in Docker

```bash
docker compose up -d --build
```

The first build compiles all four services inside containers and takes several minutes. Then:

- Kafka UI: http://localhost:8080
- order-service: http://localhost:8081/actuator/health
- inventory-service: http://localhost:8082/api/inventory
- payment-service: http://localhost:8083/api/payments
- notification-service: http://localhost:8084/api/notifications

### Option B - infrastructure in Docker, services from your IDE

Better for development: faster restarts, and you can set breakpoints.

```bash
docker compose -f docker-compose.infra.yml up -d
mvn -DskipTests install
```

Then start each service in its own terminal:

```bash
mvn -pl order-service        spring-boot:run
mvn -pl inventory-service    spring-boot:run
mvn -pl payment-service      spring-boot:run
mvn -pl notification-service spring-boot:run
```

The defaults in each `application.yml` point at `localhost:5432` and `localhost:9092`, so no
configuration is needed. Services create their own topics on startup and can be started in any
order.

### Smoke test

```powershell
./scripts/smoke-test.ps1
```

Start here. It runs six scenarios against the live system and checks each one:

1. **Happy path** - confirmed order, authorised payment, two notifications.
2. **Business rejection** - zero stock. Ends `CANCELLED`/`FAILED`, with nothing compensated,
   because no step had committed anything yet.
3. **Compensation** - payment declined after stock was reserved. Asserts the stock is genuinely
   given back, not merely that the order says `CANCELLED`.
4. **Full compensation chain** - refund, then release, in reverse order.
5. **Retries and dead-lettering** - three attempts, then the message lands in the DLT and the saga
   correctly sits still, because no reply ever arrived.
6. **Replay** - remove the cause, replay the DLT, and watch the stuck saga recover.

Every saga assertion polls for the expected state rather than sleeping a fixed time. Add
`-TimeoutSeconds 60` on a slow machine or a cold container start.

### Tests

```bash
mvn test      # unit tests; no Docker needed
mvn verify    # adds the saga integration tests, which start Postgres and Kafka via Testcontainers
```

The six integration tests in `order-service/src/test/.../OrderSagaFlowIT.java` drive the
orchestrator against a real broker and a real database, playing the participants by hand - which is
what makes it possible to send the duplicate and out-of-order messages a real broker only produces
occasionally. They cover the happy path, both compensation paths, duplicate delivery, and
out-of-phase replies.

`duplicateReplyIsIgnored` is the one to read first: delete the `firstTimeSeeing` check in
`OrderSagaOrchestrator` and it fails with two payment commands for one order.

---

## Databases

One Postgres container, four databases, four owners with separate passwords - so no service can
read another's tables even by accident.

| Service | Database | User |
| --- | --- | --- |
| order-service | `orderdb` | `order_user` / `order_pass` |
| inventory-service | `inventorydb` | `inventory_user` / `inventory_pass` |
| payment-service | `paymentdb` | `payment_user` / `payment_pass` |
| notification-service | `notificationdb` | `notification_user` / `notification_pass` |

Schemas are Flyway migrations (`src/main/resources/db/migration`), and `ddl-auto` is `none`
everywhere. Hibernate never alters a schema.

```bash
docker exec -it oms-postgres psql -U order_user -d orderdb -c "select id, status from orders;"
```

## Operations endpoints

On `order-service`:

| Endpoint | What it does |
| --- | --- |
| `GET /api/ops/dlt` | Per dead-letter topic: `total` ever written, and `pendingReplay` still to drain |
| `GET /api/ops/dlt/{topic}` | Read a DLT without consuming it, with the failure reason |
| `POST /api/ops/dlt/{topic}/replay` | Push dead-lettered messages back to the origin topic |
| `GET /api/ops/outbox` | Outbox counts by status, plus recent failures |
| `GET /api/orders/status/summary` | How many sagas are in each state |

Replay is safe because every consumer is idempotent - that is the point of building the two
patterns together.

`total` never decreases, because Kafka is a log and replaying a record does not remove it.
`pendingReplay` is measured against the replay group's committed offsets, so it does go back to
zero - that is the number to alert on.

On `inventory-service`:

| Endpoint | What it does |
| --- | --- |
| `GET /api/inventory` | Stock levels, available vs reserved |
| `PUT /api/inventory/{productId}` | Set stock, e.g. `{"availableQty": 50}` |
| `GET /api/inventory/reservations/{orderId}` | What is held for an order, and whether it was released |
| `PUT /api/inventory/simulation` | Retarget or clear the simulated failure, e.g. `{"failingProductId": ""}` |

That last one is a test hook. It exists because the dead-letter workflow is *fix the cause, then
replay*, and having to restart the service in between would make the replay demo much less
convincing.

## Deliberately not included

Skipped because they add ceremony rather than understanding: API gateway, service discovery,
Spring Cloud Config, Kubernetes, gRPC, Avro plus Schema Registry, distributed tracing.
`docs/DESIGN.md` lists what each would add and when it starts to be worth it.