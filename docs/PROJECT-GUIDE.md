# The Complete Project Guide — Event-Driven Order Management System

> **Who this is for:** anyone, including a complete beginner, who wants to understand *what* this
> project does, *how* every piece works, *which* technologies it uses, and *why* each decision was
> made. You should be able to understand the whole system by reading this document alone.
>
> **How to read it:** Parts 1–3 build up the background ideas. Parts 4–6 describe the technology and
> the code layout. Parts 7–9 follow an order through the system step by step, including what happens
> when things go wrong. Parts 10–16 are reference material: each service, the databases, the
> configuration, the infrastructure, how to run it, how it is tested, and the API. Part 17 is a
> glossary. If a word confuses you, look there first.
>
> The two shorter documents next to this one are still worth reading afterwards:
> `DESIGN.md` (the design decisions in condensed form) and `DEMO.md` (a scripted demo).

---

## Table of contents

1. [What this project is, in plain words](#1-what-this-project-is-in-plain-words)
2. [Background concepts you need first](#2-background-concepts-you-need-first)
3. [The core problem this project solves](#3-the-core-problem-this-project-solves)
4. [The technology stack and why each piece is used](#4-the-technology-stack-and-why-each-piece-is-used)
5. [Repository layout, file by file](#5-repository-layout-file-by-file)
6. [Architecture: the big picture](#6-architecture-the-big-picture)
7. [The five patterns that make it safe](#7-the-five-patterns-that-make-it-safe)
8. [End-to-end walkthrough: the happy path](#8-end-to-end-walkthrough-the-happy-path)
9. [End-to-end walkthrough: when things go wrong](#9-end-to-end-walkthrough-when-things-go-wrong)
10. [Each service in detail](#10-each-service-in-detail)
11. [The shared modules in detail](#11-the-shared-modules-in-detail)
12. [Databases and tables](#12-databases-and-tables)
13. [Configuration explained line by line](#13-configuration-explained-line-by-line)
14. [Infrastructure: Docker, Compose, Kafka, Postgres](#14-infrastructure-docker-compose-kafka-postgres)
15. [Running the project](#15-running-the-project)
16. [Testing](#16-testing)
17. [API reference](#17-api-reference)
18. [Observing and debugging](#18-observing-and-debugging)
19. [Design trade-offs and what is deliberately missing](#19-design-trade-offs-and-what-is-deliberately-missing)
20. [Glossary](#20-glossary)
21. [Suggested learning path and exercises](#21-suggested-learning-path-and-exercises)

---

## 1. What this project is, in plain words

Imagine an online shop. When a customer clicks **"Place order"**, three things have to happen:

1. **Reserve the stock** — make sure the items exist and hold them so nobody else buys them.
2. **Take the payment** — charge the customer.
3. **Confirm the order** — and tell the customer by email.

If everything were in one program with one database, this would be easy: do all three inside one
database transaction, and if anything fails, roll the whole thing back.

This project deliberately does **not** work that way. It splits the shop into **four separate
programs (microservices)**, each with **its own database**, which talk to each other by sending
**messages** through **Apache Kafka**:

| Service | Its job | Port | Its database |
| --- | --- | --- | --- |
| `order-service` | Accepts orders from customers and *coordinates* the whole process | 8081 | `orderdb` |
| `inventory-service` | Owns stock levels; reserves and releases stock | 8082 | `inventorydb` |
| `payment-service` | Owns payments; charges and refunds | 8083 | `paymentdb` |
| `notification-service` | Sends (simulated) emails to the customer | 8084 | `notificationdb` |

Because the steps now live in different programs and different databases, **there is no single
transaction that can cover them all**. That creates hard problems: What if the payment fails after
the stock was reserved? What if a program crashes halfway? What if a message is delivered twice?

**The real subject of this project is how to solve those problems correctly.** It does this with
five well-known patterns — *Saga*, *Transactional Outbox*, *Idempotent Consumer*,
*Retry + Dead-Letter Topic*, and *Saga timeouts* — all explained in detail in [Part 7](#7-the-five-patterns-that-make-it-safe).

It is a learning / portfolio project: the business logic is intentionally simple (no real payment
gateway, no real email), so that the *distributed-systems* logic is easy to see.

---

## 2. Background concepts you need first

If you already know these, skip ahead. If not, this section is the foundation for everything else.

### 2.1 Monolith vs. microservices

- A **monolith** is one program that does everything. One codebase, one deployment, usually one
  database. Simple to build and reason about.
- **Microservices** split the system into several small programs, each owning one business area
  (orders, stock, payments…). Each can be deployed, scaled and changed independently, and a team can
  own one without touching the others.

The price of microservices is that **things that used to be a simple method call become a network
conversation**, and things that used to be one database transaction become several. This project is
a study of paying that price correctly.

### 2.2 Database transactions and ACID

A **transaction** is a group of database changes that succeed or fail together. If you transfer
money, "subtract from A" and "add to B" must both happen or neither must happen.

Databases like PostgreSQL guarantee **ACID** inside one database:

- **A**tomic — all or nothing.
- **C**onsistent — rules (constraints) are never violated.
- **I**solated — concurrent transactions don't see each other's half-done work.
- **D**urable — once committed, it survives a crash.

**Key point:** ACID only works *inside one database*. As soon as you have two databases (or a
database plus Kafka), there is no built-in atomicity between them.

In Spring, you mark a method `@Transactional`, and everything that method does to the database is
one transaction: it commits when the method returns normally and rolls back if it throws an exception.

### 2.3 Synchronous vs. asynchronous communication

- **Synchronous (e.g. REST/HTTP call):** service A calls service B and *waits* for the answer. If B
  is slow or down, A is slow or broken too. The services are tightly coupled.
- **Asynchronous (messaging):** service A drops a message into a *message broker* and carries on. B
  picks it up whenever it's ready. If B is down, the message waits safely in the broker. The services
  are loosely coupled.

This project uses REST only at the edges (customers placing orders, humans inspecting state) and
**asynchronous messaging for everything between services**.

### 2.4 What is a message broker, and what is Kafka?

A **message broker** is a server whose job is to receive messages from producers and hand them to
consumers reliably. **Apache Kafka** is the most widely used one for event-driven systems.

Kafka vocabulary you must know:

| Term | Meaning | In this project |
| --- | --- | --- |
| **Producer** | A program that writes messages | Every service's `OutboxPublisher` |
| **Consumer** | A program that reads messages | Every `@KafkaListener` method |
| **Topic** | A named stream of messages, like a folder | e.g. `order.commands.reserve-inventory` |
| **Partition** | A topic is split into N partitions for parallelism. Each partition is an ordered, append-only log | Every topic has 3 partitions |
| **Offset** | A message's position number inside a partition (0, 1, 2…) | Consumers remember the last offset they processed |
| **Key** | An optional value attached to a message. Messages with the same key always go to the same partition | Always the `orderId` |
| **Consumer group** | A named set of consumers sharing the work of reading a topic. Each message is processed by *one* member of each group. Different groups each get their *own* copy of every message | e.g. `inventory-service.reserve-inventory` |
| **Commit (offset commit)** | A consumer telling Kafka "I've finished up to offset X" so it won't get those again after a restart | Done after each record (`ack-mode: record`) |
| **Broker** | One Kafka server | One broker locally, `oms-kafka` |

Two crucial facts about Kafka:

1. **Kafka is a log, not a queue.** Reading a message does *not* delete it. Messages stay until a
   retention period expires. Each consumer group just tracks *its own position* (offset) in the log.
2. **Ordering is only guaranteed within one partition.** That's why every message in this project
   is keyed by `orderId`: all messages about the same order land in the same partition and are
   therefore processed in order.

### 2.5 Commands vs. events vs. replies

This project uses three kinds of messages, and the difference matters:

- **Command** — an instruction to one specific service: *"Reserve stock for order 42."* Written in
  the imperative. Exactly one service is expected to act on it.
- **Reply** — a service reporting back the outcome of a command: *"Reservation for order 42
  SUCCEEDED."*
- **Event** — a statement of fact about something that already happened: *"Order 42 was
  confirmed."* Written in past tense. Anyone may listen; nobody is obliged to.

### 2.6 Delivery guarantees: at-most-once, at-least-once, exactly-once

When a message travels over a network and programs can crash at any moment, there are only three
possible guarantees:

- **At-most-once:** a message may be lost but never duplicated.
- **At-least-once:** a message is never lost, but may arrive **more than once**.
- **Exactly-once:** never lost, never duplicated — very hard end-to-end, and only possible within
  narrow limits.

**This project uses at-least-once everywhere** and then makes every consumer **idempotent** (safe to
run twice) so that duplicates are harmless. "At-least-once + idempotent consumer" is how most real
systems achieve the *effect* of exactly-once.

### 2.7 Idempotency

An operation is **idempotent** if doing it twice has the same effect as doing it once.
"Set the light to ON" is idempotent. "Toggle the light" is not. "Charge the customer £50" is
*not* idempotent unless you make it so — and making it so is a big part of this project.

### 2.8 Eventual consistency

Because the steps happen one after another across different services, there are moments where the
system is "in between": stock is reserved but payment hasn't happened yet. The system is not
*instantly* consistent, but it is guaranteed to *eventually* reach a consistent final state
(confirmed, or cancelled with everything undone). This is called **eventual consistency**.

That's why `POST /api/orders` returns **202 Accepted** ("I've got it, I'm working on it") rather
than **201 Created** ("it's done"). The client polls `GET /api/orders/{id}` to see the outcome.

---

## 3. The core problem this project solves

### 3.1 The distributed transaction problem

Placing an order changes data in **three databases** (`orderdb`, `inventorydb`, `paymentdb`). There
is no transaction spanning all three. So:

- If payment fails **after** stock was reserved, the stock must be **given back** — by someone,
  deliberately.
- If the order is confirmed but the final step fails, the payment must be **refunded** and the
  stock **released** — in the right order.

The classic textbook answer, **two-phase commit (2PC / XA)**, locks resources across all
participants until everyone agrees. It's slow, fragile (one dead participant blocks everyone), and
Kafka doesn't support it with databases anyway. This project rejects it and uses a **Saga** instead
([7.1](#71-saga-orchestration)).

### 3.2 The dual-write problem

Even inside *one* service there is a trap. Look at this innocent code:

```java
orderRepository.save(order);   // write #1 → PostgreSQL
kafkaTemplate.send(event);     // write #2 → Kafka
```

These are **two writes to two different systems**. If the program crashes between them, the order
exists in the database but the message was never sent — **silently**, with no error anywhere. The
order would sit there forever. Reverse the order and you get the opposite bug: a message about an
order that doesn't exist.

This project solves it with the **Transactional Outbox** pattern ([7.2](#72-transactional-outbox)).

### 3.3 The duplicate-message problem

Kafka guarantees at-least-once delivery. Duplicates happen in normal operation: when consumers
restart, when a group rebalances, when an offset commit fails, or when the outbox republishes. A
duplicate "process payment" command must **not** charge the customer twice.

Solved by the **Idempotent Consumer** pattern ([7.3](#73-idempotent-consumer)).

### 3.4 The poison-message problem

What if a message *can't* be processed — the database is down, or the message is malformed? If the
consumer just keeps failing, it blocks everything behind it in the partition forever.

Solved by **Retries + Dead-Letter Topics + Replay** ([7.4](#74-retries-and-dead-letter-topics-dlt)).

### 3.5 The silent-hang problem

What if a service receives a command, crashes, and never replies? The coordinator waits forever.

Solved (detected) by **Saga timeouts / the stuck-saga monitor** ([7.5](#75-saga-timeouts-the-stuck-saga-monitor)).

---

## 4. The technology stack and why each piece is used

| Technology | Version | What it is | Why it's used here |
| --- | --- | --- | --- |
| **Java** | 17 | Programming language | Long-term-support version; records (`record`) make message classes short and immutable |
| **Spring Boot** | 3.5.6 | Framework for building Java services | Auto-configures web server, database, Kafka, etc. so the code focuses on business logic |
| **Spring Web (MVC)** | via Boot | REST controllers | The HTTP API for placing orders and inspecting state |
| **Spring Data JPA + Hibernate** | via Boot | Object–relational mapping | Map Java classes (`Order`, `Payment`…) to tables; repositories generate SQL from method names |
| **Bean Validation (Jakarta Validation)** | via Boot | Annotation-based input validation | Rejects bad requests (`@NotBlank`, `@Min`…) before any business logic runs |
| **Spring Boot Actuator** | via Boot | Operational endpoints | `/actuator/health` tells Docker and the smoke test whether a service is up |
| **Spring for Apache Kafka** | via Boot | Kafka integration | `@KafkaListener`, `KafkaTemplate`, error handlers, dead-letter publishing |
| **Apache Kafka** (Confluent image) | 7.7.1 (Kafka 3.7) | Message broker | Durable, ordered, replayable messaging between services |
| **KRaft mode** | — | Kafka without ZooKeeper | One container instead of two; ZooKeeper is deprecated |
| **PostgreSQL** | 16 | Relational database | ACID transactions, row locks, `FOR UPDATE SKIP LOCKED`, constraints |
| **Flyway** | via Boot | Database migrations | Versioned SQL scripts create and evolve the schema reproducibly |
| **HikariCP** | via Boot | JDBC connection pool | Reuses database connections instead of opening one per query |
| **Jackson** | via Boot | JSON library | Serializes messages to JSON for Kafka and for the outbox table |
| **Maven** | 3.9+ | Build tool | Multi-module build: two shared libraries + four services |
| **Docker** | — | Containers | Runs Postgres, Kafka and the services identically on any machine |
| **Docker Compose** | v2 | Multi-container orchestration | One command starts the whole system |
| **Kafka UI** (provectus) | latest | Web UI for Kafka | Browse topics, messages, consumer groups at http://localhost:8080 |
| **Testcontainers** | via Boot | Real Docker containers in tests | Integration tests run against a real Postgres and a real Kafka |
| **JUnit 5 + AssertJ** | via Boot | Test framework + assertions | Writing and asserting tests |
| **Maven Failsafe** | via Boot | Runs `*IT` integration tests | Keeps Docker-dependent tests out of the fast `mvn test` |
| **PowerShell** | 5.1+ | Script language | `scripts/smoke-test.ps1` drives the live system end to end |

### 4.1 Each technology in more depth

#### Java 17 and records
Java `record`s (e.g. `public record SagaReply(UUID messageId, ...)`) are immutable data classes
with constructor, getters, `equals`, `hashCode` and `toString` generated automatically. They are
perfect for messages: a message should never change after it's created.

#### Spring Boot
Spring Boot is "Spring with sensible defaults". You add a dependency (a *starter*) and Boot
configures it. For example, adding `spring-boot-starter-data-jpa` plus a datasource URL in
`application.yml` gives you a working connection pool, Hibernate and repositories with zero code.

Key Spring ideas used everywhere in this code:

- **Beans and dependency injection.** Classes annotated `@Service`, `@Component`, `@RestController`
  or `@Configuration` are created by Spring and handed to each other through constructors. You never
  write `new OrderService(...)` yourself.
- **`@Transactional`.** Wraps a method in a database transaction (see [2.2](#22-database-transactions-and-acid)).
  `Propagation.MANDATORY` (used by the outbox and idempotency guard) means *"I refuse to run unless
  a transaction is already open"* — a safety net.
- **`@Scheduled`.** Runs a method periodically (the outbox poller every 500 ms, the stuck-saga
  monitor every 30 s). Enabled by `@EnableScheduling` in `OutboxConfig`.
- **`@Value("${...}")`.** Injects a configuration value from `application.yml`, with a default after
  the colon, e.g. `@Value("${oms.outbox.batch-size:50}")`.

#### Spring Data JPA / Hibernate
**JPA** (Jakarta Persistence API) is the Java standard for mapping objects to tables. **Hibernate**
is the implementation. You annotate a class with `@Entity` and `@Table`, fields with `@Column`, and
Hibernate turns `repository.save(order)` into `INSERT`/`UPDATE` statements.

**Spring Data** repositories are interfaces like:

```java
public interface PaymentRepository extends JpaRepository<Payment, UUID> {
    Optional<Payment> findByOrderId(UUID orderId);
}
```

Spring generates the implementation, deriving SQL from the method name
(`findByOrderId` → `select ... where order_id = ?`).

Other JPA features used:
- `@Version` — **optimistic locking**: a version number column that Hibernate checks on every
  update so two transactions can't silently overwrite each other.
- `@Lock(PESSIMISTIC_WRITE)` — **pessimistic locking**: `SELECT ... FOR UPDATE`, locking the row
  until the transaction ends.
- `@EntityGraph` — load an order *and* its items in one query.
- **Dirty checking** — inside a transaction, if you change a field on a loaded entity (e.g.
  `order.markStatus(...)`), Hibernate writes the change automatically at commit. That's why the
  orchestrator never calls `save()` after changing state.

#### Apache Kafka
Chosen over alternatives (RabbitMQ, ActiveMQ, SQS) because:
- It is a **durable log**: messages survive restarts and can be *re-read*, which is what makes
  dead-letter replay and catch-up after downtime possible.
- **Per-key ordering** via partitions — essential for a per-order state machine.
- **Consumer groups** let several services independently read the same events.
- It's the industry standard for event-driven microservices.

#### PostgreSQL
Chosen because it offers real ACID transactions, rich constraints (`UNIQUE`, `CHECK`, foreign keys),
row-level locking and `FOR UPDATE SKIP LOCKED` — all of which this project relies on as *hard
guarantees* that hold even if the Java code has a bug.

#### Flyway
Instead of letting Hibernate guess the schema (`ddl-auto: update`), every table is created by a
numbered SQL file (`V1__init_order_schema.sql`, `V2__seed_inventory.sql`). On startup Flyway runs any
file it hasn't run before and records it in a `flyway_schema_history` table.
**Why:** the schema is reviewable, versioned in Git, reproducible, and can contain data migrations.
`ddl-auto: update` can't do any of that and is dangerous in production.

#### Docker & Docker Compose
Every piece runs in a container so the whole system starts with one command and behaves the same on
every machine. Compose describes the containers, their ports, environment variables, health checks,
networks and start-up order.

#### Testcontainers
Starts *real* Postgres and Kafka Docker containers during tests, instead of fakes or in-memory
substitutes. The behaviour under test — transactions + outbox + broker together — can only be
trusted when tested against the real things.

---

## 5. Repository layout, file by file

```
order-management/
├── pom.xml                          Parent Maven POM: lists the 6 modules, Java 17, Spring Boot 3.5.6
├── README.md                        Quick overview + run instructions
├── docker-compose.yml               Everything: Postgres, Kafka, Kafka UI, 4 services
├── docker-compose.infra.yml         Only Postgres + Kafka + Kafka UI (run services from your IDE)
├── .dockerignore / .gitignore
│
├── infra/postgres/init/
│   └── 01-create-databases.sql      Creates 4 databases and 4 users on first Postgres start
│
├── scripts/
│   └── smoke-test.ps1               Runs 6 scenarios against the live system and checks results
│
├── docs/
│   ├── PROJECT-GUIDE.md             (this file)
│   ├── DESIGN.md                    Condensed design decisions and trade-offs
│   └── DEMO.md                      Step-by-step demo script
│
├── common-events/                   SHARED LIBRARY #1: the "wire contract" (message shapes)
│   └── src/main/java/com/oms/common/events/
│       ├── Topics.java              Every topic name, in one place
│       ├── MessageHeaders.java      Custom Kafka header names (X-Message-Id, X-Order-Id, ...)
│       ├── ReserveInventoryCommand.java   Command: hold stock
│       ├── ReleaseInventoryCommand.java   Command: give stock back (compensation)
│       ├── ProcessPaymentCommand.java     Command: charge the customer
│       ├── RefundPaymentCommand.java      Command: refund (compensation)
│       ├── SagaReply.java           The single reply type for every step
│       ├── SagaStep.java            Enum: RESERVE_INVENTORY, PROCESS_PAYMENT, REFUND_PAYMENT, RELEASE_INVENTORY
│       ├── ReplyStatus.java         Enum: SUCCEEDED, FAILED
│       ├── OrderEvent.java          Event: a fact about an order
│       ├── OrderEventType.java      Enum: ORDER_CREATED, ORDER_CONFIRMED, ORDER_CANCELLED
│       └── OrderLine.java           One product line (productId, quantity, unitPrice)
│
├── common-messaging/                SHARED LIBRARY #2: reusable messaging machinery
│   └── src/main/java/com/oms/common/messaging/
│       ├── OutboxRecorder.java      Writes a message into the outbox table (inside a transaction)
│       ├── OutboxPublisher.java     Scheduled poller: outbox table → Kafka
│       ├── OutboxMessage.java       JPA entity for the outbox table
│       ├── OutboxRepository.java    Includes the FOR UPDATE SKIP LOCKED query
│       ├── OutboxStatus.java        PENDING, SENT, FAILED
│       ├── IdempotencyGuard.java    "Have I processed this message before?"
│       ├── ProcessedMessage.java    JPA entity for the idempotency ledger
│       ├── ProcessedMessageId.java  Composite key (message_id, consumer_group)
│       ├── ProcessedMessageRepository.java
│       ├── RetryableMessageException.java     "Try again later might work"
│       ├── NonRetryableMessageException.java  "This will never work; dead-letter it now"
│       ├── SimulatedFailureException.java     Thrown on purpose by the test hooks
│       └── config/
│           ├── KafkaProducerConfig.java       Two KafkaTemplates (JSON + raw bytes)
│           ├── KafkaErrorHandlingConfig.java  Retry with backoff, then dead-letter
│           ├── KafkaTopicsConfig.java         Creates all 12 topics on startup
│           ├── KafkaConnectionConfigSupport.java  Fixes broker address for hand-built clients
│           └── OutboxConfig.java              Turns on @Scheduled
│
├── order-service/                   SERVICE: public API + saga orchestrator
│   ├── Dockerfile
│   └── src/
│       ├── main/java/com/oms/order/
│       │   ├── OrderServiceApplication.java   main(); scans com.oms so shared beans load
│       │   ├── api/OrderController.java       POST/GET /api/orders
│       │   ├── api/GlobalExceptionHandler.java  Turns exceptions into 400/404 JSON
│       │   ├── api/dto/CreateOrderRequest.java  Validated request body
│       │   ├── api/dto/OrderResponse.java       Response body
│       │   ├── domain/Order.java, OrderItem.java, OrderStatus.java
│       │   ├── domain/SagaInstance.java, SagaState.java   The saga's persisted state
│       │   ├── repository/OrderRepository.java, SagaInstanceRepository.java
│       │   ├── service/OrderService.java       placeOrder(): starts the saga
│       │   ├── service/OrderNotFoundException.java
│       │   ├── saga/OrderSagaOrchestrator.java  THE STATE MACHINE (most important file)
│       │   ├── saga/SagaReplyListener.java      Kafka listener for order.replies
│       │   ├── saga/StuckSagaMonitor.java       Logs sagas that stopped moving
│       │   └── ops/                             Operator endpoints: DLT peek/replay, outbox stats
│       ├── main/resources/application.yml
│       ├── main/resources/db/migration/V1__init_order_schema.sql
│       └── test/java/com/oms/order/
│           ├── AbstractSagaIT.java              Test base: real Postgres + Kafka
│           └── OrderSagaFlowIT.java             6 saga integration tests
│
├── inventory-service/               SERVICE: saga participant for stock
│   └── src/main/java/com/oms/inventory/
│       ├── api/InventoryController.java         Stock views, restock, simulator switch
│       ├── domain/InventoryItem.java, InventoryReservation.java, ReservationStatus.java
│       ├── messaging/InventoryCommandListener.java   Listens to reserve + release commands
│       ├── repository/...                       Includes the SELECT ... FOR UPDATE query
│       └── service/InventoryService.java, FailureSimulator.java
│   (+ V1 schema migration, V2 seed data with 8 products)
│
├── payment-service/                 SERVICE: saga participant for money
│   └── src/main/java/com/oms/payment/
│       ├── api/PaymentController.java
│       ├── domain/Payment.java, PaymentStatus.java
│       ├── messaging/PaymentCommandListener.java    Listens to process + refund commands
│       ├── repository/PaymentRepository.java
│       └── service/PaymentService.java, FailureSimulator.java
│
└── notification-service/            SERVICE: pure event subscriber
    └── src/main/java/com/oms/notification/
        ├── api/NotificationController.java
        ├── domain/Notification.java
        ├── messaging/OrderEventListener.java    Listens to order.events
        ├── repository/NotificationRepository.java
        └── service/NotificationService.java     Renders and stores "emails"
```

### 5.1 Why every service has the same internal layout

Each service is organised in **layers**:

| Package | Responsibility | Talks to |
| --- | --- | --- |
| `api` | HTTP controllers and request/response classes (DTOs) | `service` |
| `messaging` | Kafka listeners — thin, they only hand the message to a service | `service` |
| `service` | Business logic and transaction boundaries (`@Transactional`) | `domain`, `repository`, outbox, idempotency guard |
| `domain` | JPA entities with behaviour (`reserve()`, `refund()`, `cancel()`) | — |
| `repository` | Spring Data interfaces (database access) | database |

Listeners and controllers are kept **thin** on purpose: everything important happens in a
`@Transactional` service method, so it is all inside one database transaction.

### 5.2 Why there are two shared modules

- **`common-events`** contains *only* message shapes and topic names — no logic. Every service
  depends on it so that producer and consumer agree on what a `SagaReply` looks like. (In a large
  company you might instead use Avro + Schema Registry; see [Part 19](#19-design-trade-offs-and-what-is-deliberately-missing).)
- **`common-messaging`** contains the reusable *machinery*: outbox, idempotency guard, error
  handling, topic creation. Writing it once means all four services get identical, correct behaviour.

Each service's main class has `@SpringBootApplication(scanBasePackages = "com.oms")` plus
`@EntityScan` and `@EnableJpaRepositories` listing both its own package and
`com.oms.common.messaging`. **Why:** by default Spring only scans the application's own package
(`com.oms.order`), so the shared outbox and ledger classes in `com.oms.common.messaging` would be
invisible without this.

---

## 6. Architecture: the big picture

### 6.1 Component diagram

```
                     Customer / client (curl, PowerShell, browser)
                                     │
                         POST /api/orders   GET /api/orders/{id}
                                     │
                                     ▼
  ┌──────────────────────────────────────────────────────────────────────────┐
  │                         order-service  :8081                             │
  │  OrderController → OrderService ─┐                                       │
  │                                  │ writes                                │
  │  SagaReplyListener → OrderSagaOrchestrator ─┤                            │
  │                                  ▼                                       │
  │       orderdb: orders, order_items, saga_instance, outbox, processed_msg │
  │                                  │                                       │
  │                     OutboxPublisher (every 500 ms)                       │
  └──────────────────────────────────┬───────────────────────────────────────┘
                                     │ publishes
                                     ▼
  ╔══════════════════════════════════════════════════════════════════════════╗
  ║                            Apache Kafka  :9092                           ║
  ║  order.commands.reserve-inventory   order.commands.process-payment       ║
  ║  order.commands.release-inventory   order.commands.refund-payment        ║
  ║  order.replies                      order.events                         ║
  ║  (+ a .DLT dead-letter twin of each of the six)                          ║
  ╚══════╤══════════════════════╤═══════════════════════╤════════════════════╝
         │ reserve/release      │ process/refund        │ order.events
         ▼                      ▼                       ▼
  ┌──────────────────┐   ┌──────────────────┐   ┌────────────────────────┐
  │ inventory-service│   │ payment-service  │   │ notification-service   │
  │      :8082       │   │      :8083       │   │        :8084           │
  │  replies via     │   │  replies via     │   │  never replies         │
  │  its own outbox ─┼─┐ │  its own outbox ─┼─┐ │                        │
  └────────┬─────────┘ │ └────────┬─────────┘ │ └───────────┬────────────┘
           ▼           │          ▼           │             ▼
      inventorydb      │      paymentdb       │       notificationdb
                       └──────────┴───────────┴──► order.replies ──► order-service
```

### 6.2 Who talks to whom

| Topic | Kind | Producer | Consumer (group) |
| --- | --- | --- | --- |
| `order.commands.reserve-inventory` | command | order-service | inventory-service (`inventory-service.reserve-inventory`) |
| `order.commands.release-inventory` | command (compensation) | order-service | inventory-service (`inventory-service.release-inventory`) |
| `order.commands.process-payment` | command | order-service | payment-service (`payment-service.process-payment`) |
| `order.commands.refund-payment` | command (compensation) | order-service | payment-service (`payment-service.refund-payment`) |
| `order.replies` | reply | inventory-service, payment-service | order-service (`order-service.saga-replies`) |
| `order.events` | event | order-service | notification-service (`notification-service.order-events`) |
| `<any of the above>.DLT` | dead letters | Spring's error handler in the consuming service | Nobody automatically; humans via `/api/ops/dlt` |

**Important architectural rules:**

1. **No service ever reads another service's database.** It's enforced, not just encouraged: each
   database has its own user/password ([12](#12-databases-and-tables)).
2. **No service calls another service over HTTP.** All inter-service communication is Kafka.
3. **Every message is keyed by `orderId`** → per-order ordering.
4. **No service sends to Kafka directly.** Everything goes through the outbox.
5. **Every consumer is idempotent.**

### 6.3 Ports at a glance

| What | URL |
| --- | --- |
| Kafka UI | http://localhost:8080 |
| order-service | http://localhost:8081 |
| inventory-service | http://localhost:8082 |
| payment-service | http://localhost:8083 |
| notification-service | http://localhost:8084 |
| PostgreSQL | localhost:5432 |
| Kafka (from your machine) | localhost:9092 |
| Kafka (from inside Docker) | kafka:19092 |

---

## 7. The five patterns that make it safe

### 7.1 Saga (orchestration)

#### What a saga is
A **saga** is a long business process broken into a sequence of **local transactions**, one per
service. Each step commits immediately in its own database. If a later step fails, the earlier
steps are undone by **compensating actions** — new business operations that semantically reverse
them (a *refund* reverses a *charge*; a *release* reverses a *reservation*).

Note: compensation is **not a rollback**. The payment really happened and then a refund really
happened. Both are recorded. That's actually what an auditor and a customer want to see.

#### Orchestration vs. choreography
There are two styles:

- **Choreography:** no coordinator. Each service listens to events and decides what to do next
  ("I saw *StockReserved*, so I'll charge"). Simple at first, but the overall process is invisible —
  it exists only as the sum of many subscriptions spread across services.
- **Orchestration (used here):** one coordinator — the **orchestrator** — explicitly tells each
  service what to do and waits for the reply. The whole process lives in **one class**
  (`OrderSagaOrchestrator`) and its current position is stored in **one table** (`saga_instance`).

**Why orchestration here:** "Why is order 42 stuck?" becomes a single SQL query instead of
correlating four services' logs; compensation logic is in one place; adding a step changes one file.
The cost is that the orchestrator knows about every participant.

#### The state machine

```
  AWAITING_INVENTORY ──ok──► AWAITING_PAYMENT ──ok──► [local confirm] ──► COMPLETED
         │                         │                         │
       fail                      fail                 fail (simulated)
         │                         │                         │
         ▼                         ▼                         ▼
       FAILED          COMPENSATING_INVENTORY ◄──ok── COMPENSATING_PAYMENT
                                   │                         │
                                  ok                       fail
                                   ▼                         ▼
                              COMPENSATED            COMPENSATION_FAILED
                                   │
                                 fail ──► COMPENSATION_FAILED
```

| Saga state | Meaning | Outstanding command (`current_step`) |
| --- | --- | --- |
| `AWAITING_INVENTORY` | Waiting for inventory to reply to "reserve" | `RESERVE_INVENTORY` |
| `AWAITING_PAYMENT` | Stock held; waiting for payment reply | `PROCESS_PAYMENT` |
| `COMPLETED` | Everything succeeded | none |
| `FAILED` | Failed at the first step — nothing to undo | none |
| `COMPENSATING_PAYMENT` | Undoing the payment (refund requested) | `REFUND_PAYMENT` |
| `COMPENSATING_INVENTORY` | Undoing the reservation (release requested) | `RELEASE_INVENTORY` |
| `COMPENSATED` | Fully undone, order cancelled | none |
| `COMPENSATION_FAILED` | A compensation itself failed — **a human must look** | the failed step |

**Order status vs. saga state.** The `orders` table has its own customer-facing `status`
(`CREATED → INVENTORY_RESERVED → PAYMENT_COMPLETED → CONFIRMED`, or `CANCELLED`). The saga state
is internal machinery. Keeping them apart means the customer never sees words like
"COMPENSATING_INVENTORY".

#### Why compensate in reverse order
Compensation undoes the **most recent** commitment first: refund *then* release. If you released
the stock first, there would be a window in which the stock was sold to someone else while the
first customer was still charged for it.

#### Why the last step is "local"
After payment succeeds, confirming the order is a change only in `orderdb` — no message needed.
The request flag `simulateConfirmFailure: true` makes this local step fail on purpose; that's the
only way to exercise the **full** compensation chain (refund + release) with just two participants.

#### Defensive rule: ignore out-of-phase replies
`isOutOfPhase(saga, reply, expectedState)` checks the reply matches what the saga is *currently
waiting for*. A late or duplicated reply for a different step is **logged and ignored**, not thrown.
Throwing would send a perfectly harmless message to the dead-letter topic.

### 7.2 Transactional outbox

#### The idea
Never send to Kafka directly. Instead, **write the message into an `outbox` table in the same
database transaction as the business change**. Because it's the same database, the business change
and "the intent to send" commit together or not at all — the dual-write problem
([3.2](#32-the-dual-write-problem)) disappears.

A separate background job, the **`OutboxPublisher`**, reads pending rows and publishes them to
Kafka, retrying until it succeeds.

```
  @Transactional placeOrder()                         OutboxPublisher (every 500 ms)
  ┌────────────────────────────────┐                  ┌──────────────────────────────────┐
  │ INSERT orders                  │                  │ SELECT ... WHERE status='PENDING'│
  │ INSERT order_items             │   COMMIT         │   ORDER BY created_at            │
  │ INSERT saga_instance           │ ───────────►     │   FOR UPDATE SKIP LOCKED         │
  │ INSERT outbox (ORDER_CREATED)  │  (all or none)   │ for each row:                    │
  │ INSERT outbox (ReserveCommand) │                  │   send to Kafka, wait for ack    │
  └────────────────────────────────┘                  │   UPDATE status='SENT'           │
                                                      └──────────────────────────────────┘
```

#### How it is implemented
- **`OutboxRecorder.record(topic, key, payload, aggregateType, aggregateId)`**
  serializes the payload to JSON and inserts an `OutboxMessage` row with status `PENDING`. It also
  stores the payload's Java class name (`payload_type`) so the publisher can rebuild the exact
  object later. It is annotated `@Transactional(propagation = MANDATORY)` — if a developer ever
  calls it outside a transaction, it **fails immediately** rather than quietly reintroducing the bug.
- **`OutboxPublisher.publishPending()`** runs every `oms.outbox.poll-interval-ms` (500 ms):
  1. `claimPendingBatch` selects up to 50 `PENDING` rows in creation order with
     `FOR UPDATE SKIP LOCKED` (see below).
  2. For each row: rebuild the object from JSON, create a Kafka `ProducerRecord` with the row's key
     and headers `X-Outbox-Id` and `X-Order-Id`, send it, and **block until Kafka acknowledges**
     (up to 10 s).
  3. On success → `markSent()`. On failure → `markAttemptFailed()`: increments `attempts`, stores the
     error, and after 10 attempts parks the row as `FAILED` for an operator to inspect
     (`GET /api/ops/outbox`).

#### `FOR UPDATE SKIP LOCKED` — why
If you run two copies of a service, both pollers would grab the same rows and publish them twice.
`FOR UPDATE` locks the rows a poller is working on; `SKIP LOCKED` makes the other poller *skip*
those rows rather than wait. Each row is handled by exactly one poller. (In JPA it's expressed as
`@Lock(PESSIMISTIC_WRITE)` + lock timeout hint `-2`.)

#### The trade-off
If the publisher sends a message and then crashes before marking the row `SENT`, the row is still
`PENDING` and will be sent **again**. So the outbox gives **at-least-once** publishing. That's fine
*only because* every consumer is idempotent ([7.3](#73-idempotent-consumer)). **The outbox and the
idempotent consumer only work as a pair.** Crucially, the `messageId` is generated once and stored
inside the JSON payload, so a republished message carries the *same* id and is recognised as a
duplicate.

The production upgrade is **Change Data Capture (Debezium)** reading Postgres' write-ahead log
instead of polling — same guarantees, lower latency, no polling load.

### 7.3 Idempotent consumer

#### The idea
Every consumer keeps a ledger of the messages it has already processed. Before doing anything, it
checks the ledger. If the message is there, it's a duplicate — skip it.

#### How it is implemented
`IdempotencyGuard.firstTimeSeeing(messageId, consumerGroup, topic)`:

```java
if (repository.existsById(new ProcessedMessageId(messageId, consumerGroup))) {
    return false;                 // duplicate – caller returns without doing anything
}
repository.save(new ProcessedMessage(messageId, consumerGroup, topic));
return true;                      // first time – caller goes ahead
```

Used at the top of every handler:

```java
@Transactional
public void reserve(ReserveInventoryCommand command) {
    if (!idempotencyGuard.firstTimeSeeing(command.messageId(), GROUP_RESERVE, Topics.RESERVE_INVENTORY)) {
        return;
    }
    ... business logic ...
}
```

Three details make this correct:

1. **The ledger row is written in the same transaction as the business change.** If the business
   logic throws, the ledger row rolls back too, and the redelivered message is free to try again. If
   it were a separate transaction, the ledger could claim "done" for work that never happened.
2. **The key is `(message_id, consumer_group)`, not just `message_id`.** Two different consumers of
   the same message must each get to process it once.
3. **The database primary key is the real guarantee.** The `existsById` check is an optimisation.
   If two threads race past it at the same time, the database rejects the second insert, that
   transaction rolls back, Kafka redelivers, and the check then correctly says "duplicate".

#### Defence in depth against double-charging
The payment service has **three** layers:

1. `processed_message` — catches the *same message* twice.
2. `paymentRepository.findByOrderId(...)` — catches a *different message* asking for the same
   thing (e.g. if someone re-issued the command with a new id). It restates the earlier outcome
   instead of charging again.
3. `UNIQUE (order_id)` constraint on `payment` — holds even if both checks above had bugs.

The inventory service has the same idea: the ledger, an "already has an ACTIVE reservation?" check,
and a `UNIQUE (order_id, product_id)` constraint on reservations.

Compensations are also written to be **safe to run twice**: `Payment.refund()` does nothing if
already refunded; `InventoryItem.release()` clamps to what's actually reserved; releasing when
there's nothing to release still replies `SUCCEEDED` (otherwise the saga would be stranded).

### 7.4 Retries and dead-letter topics (DLT)

#### The idea
When a consumer fails to process a message, decide: **could trying again help?**

| Kind | Example | Treatment |
| --- | --- | --- |
| **Transient** (retryable) | DB briefly unreachable, lock timeout, gateway timeout | Retry with exponential backoff: attempt 1, wait 1 s, attempt 2, wait 2 s, attempt 3 → then dead-letter |
| **Fatal** (non-retryable) | Malformed JSON, unknown order, missing `messageId` | Dead-letter **immediately**, no retries |
| **Business "no"** | Out of stock, payment declined | **Not an error at all.** Reply `FAILED` normally |

A **dead-letter topic** (`<topic>.DLT`) is a parking lot: the failed message is copied there with
headers describing the exception, and the consumer moves on so the partition isn't blocked forever.

#### How it is implemented (`KafkaErrorHandlingConfig`)
One `DefaultErrorHandler` bean per service, which Spring Boot automatically attaches to every
`@KafkaListener`:

- `ExponentialBackOff(1000 ms, ×2.0)`, max interval 10 s, `maxAttempts = 3` (1 original + 2 retries).
- Not-retryable exceptions: `NonRetryableMessageException`, `DeserializationException`,
  `ConversionException`, `JsonProcessingException`, `IllegalArgumentException`,
  `NullPointerException`.
- A `DeadLetterPublishingRecoverer` sends the failed record to `Topics.dlt(originalTopic)`, partition
  `-1` (let Kafka choose — mirroring the source partition would break if the DLT had fewer partitions).

Supporting pieces:

- **`ErrorHandlingDeserializer`** (configured in `application.yml`) wraps the JSON deserializer.
  Without it, a malformed message would throw *inside Kafka's poll loop*, where no error handler can
  catch it: the consumer would crash, restart, read the same bad message, and crash again forever.
  With it, the failure becomes an ordinary failed record that can be dead-lettered.
- **Two `KafkaTemplate`s** (`KafkaProducerConfig`): a JSON one for normal messages and a `byte[]` one
  for dead-lettering messages that couldn't be deserialized. If you fed unreadable raw bytes into a
  JSON serializer it would base64-encode them and destroy the evidence.

#### The most common mistake this design avoids
When stock runs out, `inventory-service` **does not throw**. It replies `FAILED` with a reason.
"The answer is no" is a *successful* handling of the message. Throwing would retry it three times
and dead-letter a message that worked perfectly.

#### Blocking vs. non-blocking retry
`DefaultErrorHandler` pauses the consumer during backoff, so one bad message briefly holds up its
partition. The alternative, `@RetryableTopic`, sends failures to separate timed retry topics and
keeps the main partition flowing — better under load, but more moving parts. Switching is a change
to one bean.

#### Replay: draining the DLT
A dead-letter topic is only useful if you can empty it once the cause is fixed. `DltAdminService` in
order-service provides:

- **Peek** — `GET /api/ops/dlt/{originTopic}`: reads the DLT from the beginning *without committing*
  (consumer group `dlt-admin-peek`), showing payload and exception message.
- **Replay** — `POST /api/ops/dlt/{originTopic}/replay`: reads from where the `dlt-admin-replay`
  group last stopped, republishes each record to the **origin** topic (copying business headers but
  dropping old `kafka_dlt-*` diagnostic headers), then commits. Committing *after* republishing means
  a crash mid-replay repeats work rather than losing it — and repeating is safe because consumers
  are idempotent.
- **Depths** — `GET /api/ops/dlt`: for each DLT, `total` (everything ever written; never decreases,
  because Kafka is a log) and `pendingReplay` (what replay hasn't processed yet; this is the number to
  alert on).

The workflow is always: **fix the cause first, then replay.** Replaying first just fills the DLT again.

### 7.5 Saga timeouts: the stuck-saga monitor

If a participant crashes after consuming a command but before replying — or the command is sitting
in a DLT — no reply will ever come, and the saga sits in `AWAITING_*` forever.

`StuckSagaMonitor` runs every 30 s (`oms.saga.monitor-interval-ms`) and finds sagas in an in-flight
state that haven't changed for 2 minutes (`oms.saga.stuck-after: PT2M`). It logs:

```
STUCK SAGA saga=... order=... state=AWAITING_INVENTORY step=RESERVE_INVENTORY unchanged since ...
```

It deliberately **only reports**. Automatically resending commands or forcing compensation is a
business decision; doing it blindly is how customers get double-charged.

### 7.6 Supporting technique: locking

| Where | Lock type | Why |
| --- | --- | --- |
| `inventory_item` during reserve/release | **Pessimistic** (`SELECT ... FOR UPDATE`) | Two orders for the last unit must not both see "1 available". Locking before checking makes check-then-write atomic |
| Inventory lock *order* | Products locked in sorted (`TreeMap`) order | Two orders locking products in opposite orders would deadlock each other |
| `outbox` polling | `FOR UPDATE SKIP LOCKED` | Multiple replicas never publish the same row twice |
| `orders`, `saga_instance`, `payment`, `inventory_item` | **Optimistic** (`@Version` column) | Detects any unexpected concurrent overwrite as an error instead of silently losing an update |

Why not optimistic locking for stock? Both concurrent orders would pass the "is there enough?"
check, and one would fail at commit with a confusing version conflict instead of a clear "insufficient
stock" business answer.

---

## 8. End-to-end walkthrough: the happy path

Let's follow one order — 1 × `P-LAPTOP` at 1200.00 for customer `CUST-1001` — through every class,
table and topic.

### Step 1 — The customer places the order (order-service)

```
POST http://localhost:8081/api/orders
{
  "customerId": "CUST-1001",
  "items": [ { "productId": "P-LAPTOP", "quantity": 1, "unitPrice": 1200.00 } ]
}
```

1. **`OrderController.create`** receives the request. `@Valid` triggers Bean Validation on
   `CreateOrderRequest` (customerId not blank, at least one item, quantity ≥ 1, unitPrice ≥ 0). On
   failure, `GlobalExceptionHandler` returns **400** with a `ProblemDetail` JSON body.
2. **`OrderService.placeOrder`** runs in **one transaction**:
   - Builds an `Order` (status `CREATED`) with its `OrderItem`s; `addItem` recalculates the total (1200.00).
   - Inserts rows into `orders` and `order_items`.
   - Inserts a `saga_instance` row: state `AWAITING_INVENTORY`, current step `RESERVE_INVENTORY`.
   - Records **two outbox rows**:
     - `OrderEvent(ORDER_CREATED)` → topic `order.events`
     - `ReserveInventoryCommand(messageId, sagaId, orderId, lines)` → topic `order.commands.reserve-inventory`
   - **Commits.** No Kafka call has happened yet.
3. The controller returns **202 Accepted** with a `Location: /api/orders/{id}` header and the order
   body (`status: CREATED`, `sagaState: AWAITING_INVENTORY`).

### Step 2 — The outbox is published (order-service)

Within ~500 ms, **`OutboxPublisher.publishPending`** claims both rows, rebuilds each object from
JSON, sends them to Kafka keyed by the order id, waits for the broker's acknowledgement, and marks
them `SENT`.

Spring's `JsonSerializer` adds a `__TypeId__` header containing the Java class name, so the receiver
knows which class to deserialize into. Kafka hashes the key (the order id) to pick one of the 3
partitions; all later messages about this order go to that same partition.

### Step 3 — Two things happen in parallel

**3a. notification-service** (consumer group `notification-service.order-events`) receives
`ORDER_CREATED`. `NotificationService.handle` checks the idempotency ledger, renders
"We have your order #1a2b3c4d", saves a `notification` row and logs `EMAIL to customer ...`.
Nothing waits for this. If notification-service were down, orders would still complete and it would
catch up later from its committed offset.

**3b. inventory-service** (group `inventory-service.reserve-inventory`) receives the
`ReserveInventoryCommand`. **`InventoryService.reserve`** runs in one transaction:

1. `FailureSimulator.failIfRequested` — throws only if the order contains the trigger product (`P-BOOM`).
2. `IdempotencyGuard.firstTimeSeeing` — inserts a `processed_message` row.
3. Is there already an `ACTIVE` reservation for this order? (second line of defence) — no.
4. Merges lines per product into a sorted `TreeMap`, then for each product
   `lockByProductId` (`SELECT ... FOR UPDATE`) and checks `availableQty ≥ requested`.
5. All available → `InventoryItem.reserve(1)`: `available_qty 50→49`, `reserved_qty 0→1`;
   inserts an `inventory_reservation` row with status `ACTIVE`.
6. Records an outbox row: `SagaReply(RESERVE_INVENTORY, SUCCEEDED)` → `order.replies`.
7. Commits. The listener returns, and Spring commits the Kafka offset for this record.

Then inventory-service's own `OutboxPublisher` publishes the reply.

### Step 4 — The orchestrator advances (order-service)

**`SagaReplyListener.onSagaReply`** (group `order-service.saga-replies`) hands the reply to
**`OrderSagaOrchestrator.handle`**, which in one transaction:

1. Checks the idempotency ledger.
2. Loads the `SagaInstance` and `Order` (a missing one → `NonRetryableMessageException` → straight to DLT).
3. Switches on `reply.step()` → `onInventoryReserveReply`.
4. `isOutOfPhase`? No — saga is `AWAITING_INVENTORY` as expected.
5. Success → order status `INVENTORY_RESERVED`; saga → `AWAITING_PAYMENT` / `PROCESS_PAYMENT`;
   records `ProcessPaymentCommand(amount 1200.00)` → `order.commands.process-payment`.
6. Commits (Hibernate writes the changed entities automatically — "dirty checking").

### Step 5 — Payment (payment-service)

**`PaymentService.process`** (group `payment-service.process-payment`), one transaction:

1. Failure simulator (customer `CUST-BOOM` would throw).
2. Idempotency guard.
3. Existing payment for this order? No.
4. Amount 1200.00 ≤ 10000 (`oms.payment.auto-decline-above`) → `payment.authorize()`: status
   `AUTHORIZED`, a fake reference like `AUTH-3F9A1C2B7D4E`.
5. Inserts the `payment` row; records `SagaReply(PROCESS_PAYMENT, SUCCEEDED)` → `order.replies`.

### Step 6 — Confirmation (order-service)

`OrderSagaOrchestrator.onPaymentReply`:

1. Not out of phase (saga is `AWAITING_PAYMENT`).
2. Success → order `PAYMENT_COMPLETED`.
3. The **local** final step: `simulateConfirmFailure` is false → order `CONFIRMED`, saga
   `COMPLETED` (current step `null`).
4. Records `OrderEvent(ORDER_CONFIRMED)` → `order.events`.

### Step 7 — Second email (notification-service)

Receives `ORDER_CONFIRMED`, saves "Order #1a2b3c4d is confirmed".

### Final state

| Where | Value |
| --- | --- |
| `GET /api/orders/{id}` | `status: CONFIRMED`, `sagaState: COMPLETED` |
| `GET :8082/api/inventory/P-LAPTOP` | available 49, reserved 1 |
| `GET :8083/api/payments/order/{id}` | `AUTHORIZED` |
| `GET :8084/api/notifications/order/{id}` | 2 notifications: `ORDER_CREATED`, `ORDER_CONFIRMED` |

The whole thing typically takes 1–3 seconds, mostly the 500 ms outbox poll interval at each hop.

> Note: in this simplified model a confirmed order's stock stays in `reserved_qty`. A real shop
> would later move it out when the item is shipped; shipping is outside this project's scope.

### Sequence diagram

```
Client      order-service        Kafka            inventory-svc     payment-svc     notification-svc
  │ POST /orders  │                 │                    │                │                 │
  │──────────────►│ tx: order+saga+2 outbox rows          │                │                 │
  │◄── 202 ───────│                 │                    │                │                 │
  │               │ outbox poll ───►│ order.events       │                │                 │
  │               │                 │ (ORDER_CREATED) ───┼────────────────┼────────────────►│ email #1
  │               │                 │ reserve-inventory ►│                │                 │
  │               │                 │                    │ tx: lock, reserve, outbox reply   │
  │               │                 │◄── order.replies ──│                │                 │
  │               │◄── reply OK ────│                    │                │                 │
  │               │ tx: AWAITING_PAYMENT + outbox cmd     │                │                 │
  │               │ outbox poll ───►│ process-payment ───┼───────────────►│                 │
  │               │                 │                    │                │ tx: authorize,  │
  │               │                 │◄─────────────── order.replies ──────│   outbox reply  │
  │               │◄── reply OK ────│                    │                │                 │
  │               │ tx: CONFIRMED/COMPLETED + outbox event │               │                 │
  │               │ outbox poll ───►│ order.events (ORDER_CONFIRMED) ─────┼────────────────►│ email #2
  │ GET /orders/id│                 │                    │                │                 │
  │──────────────►│                 │                    │                │                 │
  │◄─ CONFIRMED ──│                 │                    │                │                 │
```

---

## 9. End-to-end walkthrough: when things go wrong

The seed data and test hooks let you trigger every failure path with ordinary API input.

### 9.1 Business rejection — out of stock (nothing to compensate)

Order `P-OUT-OF-STOCK` (seeded with 0 stock).

1. Inventory locks the row, sees `0 < 1`, and replies
   `FAILED: "Insufficient stock for P-OUT-OF-STOCK: requested 1, available 0"`. **No exception** —
   this is a successful handling of the message.
2. Orchestrator: reserve failed → `finish(FAILED)`: order `CANCELLED` with that reason, saga
   `FAILED`, publishes `ORDER_CANCELLED`.
3. Notification: "Order could not be completed".

Result: `CANCELLED / FAILED`. No compensation, because no step had committed anything.

### 9.2 Payment declined — compensation of one step

Order 30 × `P-MONITOR` at 400.00 = **12,000** (over the 10,000 limit; `P-MONITOR` has 30 in stock).

1. Inventory reserves 30 monitors → SUCCEEDED.
2. Orchestrator → `AWAITING_PAYMENT`, sends process-payment.
3. Payment: 12,000 > 10,000 → `payment.decline(...)`, saves a `DECLINED` payment row, replies `FAILED`.
4. Orchestrator `onPaymentReply` failure → saga `COMPENSATING_INVENTORY`, sends
   `ReleaseInventoryCommand`.
5. Inventory `release`: finds `ACTIVE` reservations for the order, locks each item,
   `item.release(30)` (reserved → available), marks reservation `RELEASED`, replies SUCCEEDED.
6. Orchestrator `onReleaseReply` → `finish(COMPENSATED)`: order `CANCELLED`, publishes `ORDER_CANCELLED`.

Result: `CANCELLED / COMPENSATED`; P-MONITOR stock is exactly back to where it started.

### 9.3 Final step fails — the full compensation chain, in reverse

Order 2 × `P-HEADPHONES` with `"simulateConfirmFailure": true`.

1. Reserve → SUCCEEDED. Payment → AUTHORIZED.
2. Orchestrator: payment OK → order `PAYMENT_COMPLETED`, then the local confirm "fails" →
   saga `COMPENSATING_PAYMENT`, sends `RefundPaymentCommand`. **Stock is still held at this point.**
3. Payment `refund` → status `REFUNDED`, replies SUCCEEDED.
4. Orchestrator `onRefundReply` → saga `COMPENSATING_INVENTORY`, sends `ReleaseInventoryCommand`.
5. Inventory releases → SUCCEEDED.
6. Orchestrator → `COMPENSATED`, order `CANCELLED`.

Result: `CANCELLED / COMPENSATED`, payment `REFUNDED`, stock restored. Refund came *before* release.

### 9.4 Technical failure — retries, then the dead-letter topic

Order `P-BOOM`. inventory-service's `FailureSimulator` throws `SimulatedFailureException`
(a `RetryableMessageException`) every time.

1. Attempt 1 fails → wait 1 s → attempt 2 fails → wait 2 s → attempt 3 fails. Each failure rolls back
   the transaction (so no ledger row is kept).
2. `DeadLetterPublishingRecoverer` copies the record to `order.commands.reserve-inventory.DLT` with
   headers like `kafka_dlt-exception-message`. The consumer moves on.
3. No reply is ever sent, so the order **correctly stays** `CREATED / AWAITING_INVENTORY`.
4. After 2 minutes, `StuckSagaMonitor` starts logging `STUCK SAGA ...`.

**Recovery:**

```powershell
# 1. Remove the cause (disable the simulator at runtime)
Invoke-RestMethod -Method Put -Uri http://localhost:8082/api/inventory/simulation `
  -ContentType 'application/json' -Body '{"failingProductId":""}'

# 2. Replay the DLT back onto the original topic
Invoke-RestMethod -Method Post -Uri http://localhost:8081/api/ops/dlt/order.commands.reserve-inventory/replay
```

The command flows again, inventory reserves `P-BOOM`, and the stuck order finishes `CONFIRMED /
COMPLETED`. `GET /api/ops/dlt` then shows `total = 1` (Kafka keeps the record) and
`pendingReplay = 0`. Replaying again reports `"Nothing left to replay"`.

There is a matching hook in payment-service: customer id `CUST-BOOM` throws a retryable error and
dead-letters `order.commands.process-payment` (that one is configured in `application.yml` and is not
switchable at runtime).

### 9.5 Duplicate messages

If the same `SagaReply` (same `messageId`) arrives twice, the second call to
`firstTimeSeeing` finds the ledger row and returns `false`; the orchestrator does nothing. Without
this check, the duplicate "reserve succeeded" would emit a **second payment command** — the customer
charged twice. The integration test `duplicateReplyIsIgnored` proves this.

### 9.6 "What if it crashes right here?" — a full table

This is the heart of why the design is the way it is.

| Crash point | What happens | Why it's safe |
| --- | --- | --- |
| order-service crashes during `placeOrder` before commit | Nothing saved; client gets an error and can retry | Single transaction |
| After commit, before the outbox is published | Rows stay `PENDING`; published when the service restarts | Outbox |
| Publisher sent to Kafka, crashed before marking `SENT` | Message published again on restart with the **same** `messageId` | Consumer's idempotency ledger drops the duplicate |
| Kafka is down | Publisher retries every poll; `attempts` grows; after 10 failures the row is parked `FAILED` | Nothing lost; visible at `GET /api/ops/outbox` |
| inventory-service crashes mid-transaction | Transaction rolls back (including ledger row); offset not committed; message redelivered | Clean retry |
| inventory-service commits, crashes before committing the Kafka offset | Message redelivered; ledger says duplicate; skipped. The reply was already in its outbox | Idempotency |
| Two copies of a service poll the outbox at once | `SKIP LOCKED` gives each row to one poller | Row locks |
| Two orders want the last unit at the same moment | The second waits on the row lock, then sees 0 available and gets a clean "insufficient stock" | Pessimistic lock |
| notification-service down for an hour | Saga unaffected; it catches up from its committed offset | Pub/sub decoupling |
| A participant never replies | Saga stays in-flight; `StuckSagaMonitor` logs it after 2 min | Visible, human decides |
| A compensation reports failure | Saga → `COMPENSATION_FAILED`; log says `MANUAL INTERVENTION REQUIRED` | No unsafe automatic guess |
| Malformed message on any topic | `ErrorHandlingDeserializer` → non-retryable → straight to `.DLT` | Partition never blocked |
| A late reply for a step no longer awaited | `isOutOfPhase` logs and ignores it | No state corruption, no DLT noise |

---

## 10. Each service in detail

### 10.1 order-service (port 8081) — the brain

**Responsibilities:** public API; owns orders; runs the saga orchestrator; publishes order events;
hosts the operator endpoints for DLTs and the outbox; monitors stuck sagas.

| Class | Role |
| --- | --- |
| `OrderController` | `POST /api/orders` (202), `GET /api/orders/{id}`, `GET /api/orders?limit=`, `GET /api/orders/status/summary` |
| `GlobalExceptionHandler` | `OrderNotFoundException` → 404; validation errors → 400, both as RFC 7807 `ProblemDetail` JSON |
| `CreateOrderRequest` / `OrderResponse` | Request/response records (DTOs). DTOs keep the API shape independent of the database entities |
| `OrderService` | `placeOrder` (starts the saga), read queries, `sagaSummary` (count per state, including zeros) |
| `Order`, `OrderItem` | Entities. `Order.addItem` keeps `totalAmount` correct; `cancel(reason)` sets `CANCELLED` |
| `SagaInstance`, `SagaState` | Persisted saga position; `transitionTo(state, step)` |
| `SagaReplyListener` | `@KafkaListener` on `order.replies`, group `order-service.saga-replies`. Thin by design |
| `OrderSagaOrchestrator` | The state machine. One transaction per reply: ledger + state change + next command |
| `StuckSagaMonitor` | `@Scheduled` report of sagas stuck > 2 min |
| `OpsController`, `DltAdminService` | DLT depth/peek/replay and outbox statistics |

The consumer group constant `OrderSagaOrchestrator.CONSUMER_GROUP` is used **both** by the listener
annotation and as the idempotency ledger key, so the two can never drift apart. (A subtle Spring
gotcha: a `@KafkaListener` with an `id` but no `groupId` silently uses the id as the group name.)

`DltAdminService` deliberately uses plain Kafka `KafkaConsumer`/`KafkaProducer` objects rather than
`@KafkaListener`, because replay is an on-demand, bounded operation triggered by an HTTP call — not a
permanently running subscription. It reads values as raw bytes so broken payloads can't break it.

### 10.2 inventory-service (port 8082) — stock

**Responsibilities:** owns stock; reserves stock for orders; releases it on compensation.

- **Stock is two numbers.** `available_qty` (can still be promised) and `reserved_qty` (held by an
  in-flight saga). Their sum is the physical count. Keeping them apart lets a reservation be undone
  exactly.
- **`inventory_reservation`** records exactly what was held per order and product, so a release
  gives back precisely that, without trusting the release command to restate quantities.
- Two listeners, two consumer groups (`inventory-service.reserve-inventory`,
  `inventory-service.release-inventory`) — so lag and offsets for reservations and releases are
  reported separately.
- `FailureSimulator` — test hook; `P-BOOM` by default; changeable via `PUT /api/inventory/simulation`.
  The field is `volatile` because it's written by an HTTP thread and read by Kafka listener threads.

Seeded products (`V2__seed_inventory.sql`):

| Product | Stock | Purpose |
| --- | --- | --- |
| `P-LAPTOP` | 50 | normal |
| `P-KEYBOARD` | 200 | normal |
| `P-MOUSE` | 200 | normal |
| `P-MONITOR` | 30 | normal; used for the decline/compensation demo |
| `P-HEADPHONES` | 120 | normal; used for the full-chain demo |
| `P-WEBCAM` | 5 | low stock |
| `P-OUT-OF-STOCK` | 0 | always rejected |
| `P-BOOM` | 100 | consumer throws → retries → DLT |

### 10.3 payment-service (port 8083) — money

**Responsibilities:** charge (authorise) and refund. No real gateway — it's simulated.

- `Payment` statuses: `AUTHORIZED`, `DECLINED`, `REFUNDED`.
- Rule: amounts above `oms.payment.auto-decline-above` (10000) are **declined** (a business outcome,
  replied as `FAILED`, never thrown).
- If a payment already exists for the order, the earlier outcome is restated rather than charging again.
- Refund with no payment on file → still `SUCCEEDED` (nothing to give back; failing would strand the saga).
- `FailureSimulator` — customer `CUST-BOOM` throws a retryable error.
- Groups: `payment-service.process-payment`, `payment-service.refund-payment`.

### 10.4 notification-service (port 8084) — the subscriber

**Responsibilities:** listen to `order.events` and "send" an email for each.

- It **never replies**, and nothing waits for it — the textbook benefit of publish/subscribe.
- Notifications are **stored** in a table (not just logged) so you can verify that a redelivered
  event produced exactly one email.
- Still uses the idempotency guard: a rebalance must not send the customer two "confirmed" emails.
- It publishes nothing, but has an (empty) `outbox` table because the shared `OutboxPublisher` bean
  polls it. Keeping an empty table was judged simpler than making the bean conditional.
- In a real system, the actual email call would happen *after* commit and use a provider-side
  idempotency key, since an external side effect can't be rolled back.

---

## 11. The shared modules in detail

### 11.1 common-events — the message contract

Every message carries a `messageId` (UUID). It is generated **once** by the producer and never
regenerated on retry — it's what the idempotency ledger keys on.

| Record | Topic | Fields |
| --- | --- | --- |
| `ReserveInventoryCommand` | `order.commands.reserve-inventory` | messageId, sagaId, orderId, lines[], occurredAt |
| `ReleaseInventoryCommand` | `order.commands.release-inventory` | messageId, sagaId, orderId, reason, occurredAt |
| `ProcessPaymentCommand` | `order.commands.process-payment` | messageId, sagaId, orderId, customerId, amount, occurredAt |
| `RefundPaymentCommand` | `order.commands.refund-payment` | messageId, sagaId, orderId, amount, reason, occurredAt |
| `SagaReply` | `order.replies` | messageId, sagaId, orderId, step, status, reason, occurredAt |
| `OrderEvent` | `order.events` | messageId, orderId, customerId, type, totalAmount, detail, occurredAt |

Why **one** `SagaReply` type for all steps instead of four reply classes? Four classes on one topic
would need polymorphic JSON handling, for no gain — the orchestrator switches on `step` anyway.

`Topics.CONSUMED_TOPICS` lists the six topics that someone consumes; each gets a `.DLT` twin.

Example JSON on the wire (`order.replies`):

```json
{
  "messageId": "6c1d2e9a-...",
  "sagaId": "b7f0...",
  "orderId": "1a2b3c4d-...",
  "step": "RESERVE_INVENTORY",
  "status": "SUCCEEDED",
  "reason": null,
  "occurredAt": "2026-10-06T10:15:30.123Z"
}
```
with Kafka headers including `__TypeId__: com.oms.common.events.SagaReply`, `X-Outbox-Id`, `X-Order-Id`.

### 11.2 common-messaging — the machinery

| Class | What it does |
| --- | --- |
| `OutboxRecorder` | `record(...)` inserts a `PENDING` outbox row. `MANDATORY` transaction |
| `OutboxPublisher` | `@Scheduled` poller: claim batch → send → wait for ack → mark `SENT`/attempt failed |
| `OutboxRepository.claimPendingBatch` | `FOR UPDATE SKIP LOCKED` query |
| `IdempotencyGuard.firstTimeSeeing` | Ledger check + insert. `MANDATORY` transaction. Missing `messageId` → non-retryable |
| `KafkaErrorHandlingConfig` | `DefaultErrorHandler` + exponential backoff + dead-letter recoverer |
| `KafkaProducerConfig` | JSON `KafkaTemplate` + byte-array `KafkaTemplate` |
| `KafkaTopicsConfig` | Declares 6 topics + 6 DLTs (3 partitions, 1 replica). `KafkaAdmin` only creates what's missing, so any service can start first |
| `KafkaConnectionConfigSupport` | Overrides `bootstrap.servers` for hand-built clients when Spring supplies the broker address at runtime (Testcontainers). Without it, tests would connect to `localhost:9092` and fail with a misleading "topic not present in metadata" |
| `OutboxConfig` | `@EnableScheduling` |
| `RetryableMessageException` / `NonRetryableMessageException` / `SimulatedFailureException` | Exception types that steer the error handler |

---

## 12. Databases and tables

### 12.1 Database per service

One Postgres container hosts four **logically isolated** databases, each owned by its own user:

| Service | Database | User / password |
| --- | --- | --- |
| order-service | `orderdb` | `order_user` / `order_pass` |
| inventory-service | `inventorydb` | `inventory_user` / `inventory_pass` |
| payment-service | `paymentdb` | `payment_user` / `payment_pass` |
| notification-service | `notificationdb` | `notification_user` / `notification_pass` |

Created by `infra/postgres/init/01-create-databases.sql`, which Postgres runs **only the first time**
its data volume is created. It also hands each user ownership of the `public` schema (Postgres 15+
no longer lets everyone create tables there).

**Why database-per-service:** if services share tables, they're secretly coupled — changing a column
breaks someone else. Separate credentials make it *impossible* for one service to read another's data.
**Why one container, not four:** four containers would cost RAM and teach nothing extra.

### 12.2 Tables

Every service database has the two infrastructure tables `outbox` and `processed_message`, plus its
own business tables.

**orderdb**

| Table | Key columns | Notes |
| --- | --- | --- |
| `orders` | id, customer_id, status, total_amount, failure_reason, simulate_confirm_failure, version | Indexes on status, created_at |
| `order_items` | id, order_id → orders, product_id, quantity (>0), unit_price (≥0) | Cascade delete |
| `saga_instance` | saga_id, order_id (UNIQUE → orders), state, current_step, failure_reason, version | Index `(state, updated_at)` serves the stuck-saga monitor |

**inventorydb**

| Table | Key columns | Notes |
| --- | --- | --- |
| `inventory_item` | product_id (PK), available_qty ≥ 0, reserved_qty ≥ 0, version | `CHECK` constraints make negative stock impossible |
| `inventory_reservation` | id, order_id, product_id → inventory_item, quantity > 0, status, released_at | `UNIQUE (order_id, product_id)` makes double reservation impossible |

**paymentdb**

| Table | Key columns | Notes |
| --- | --- | --- |
| `payment` | id, order_id **UNIQUE**, customer_id, amount ≥ 0, status, authorization_ref, failure_reason, version | The UNIQUE constraint is the final defence against double-charging |

**notificationdb**

| Table | Key columns |
| --- | --- |
| `notification` | id, order_id, customer_id, channel, event_type, subject, body, created_at |

**Infrastructure tables (in all four)**

| Table | Columns | Notes |
| --- | --- | --- |
| `outbox` | id, aggregate_type, aggregate_id, topic, message_key, payload_type, payload (JSON text), status, attempts, last_error, created_at, published_at | Index `(status, created_at)` matches the poller's query |
| `processed_message` | **PK (message_id, consumer_group)**, topic, processed_at | The composite primary key *is* the idempotency mechanism |

Money is stored as `NUMERIC(19,2)` and handled as `BigDecimal` in Java — never `double`, which
can't represent decimal amounts exactly.

### 12.3 Useful queries

```bash
docker exec -it oms-postgres psql -U order_user -d orderdb \
  -c "select o.id, o.status, s.state, s.current_step, o.failure_reason from orders o join saga_instance s on s.order_id = o.id order by o.created_at desc limit 10;"

docker exec -it oms-postgres psql -U order_user -d orderdb \
  -c "select topic, status, attempts, created_at from outbox order by created_at desc limit 10;"

docker exec -it oms-postgres psql -U inventory_user -d inventorydb \
  -c "select * from inventory_item order by product_id;"

docker exec -it oms-postgres psql -U payment_user -d paymentdb \
  -c "select order_id, amount, status, authorization_ref from payment order by created_at desc limit 10;"
```

---

## 13. Configuration explained line by line

All four `application.yml` files share the same structure. Here's order-service's, annotated:

```yaml
spring:
  application:
    name: order-service

  datasource:
    # ${ENV_VAR:default} — use the environment variable if set (Docker sets it), else the default
    # (so running from the IDE needs no configuration).
    url: ${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/orderdb}
    username: ${SPRING_DATASOURCE_USERNAME:order_user}
    password: ${SPRING_DATASOURCE_PASSWORD:order_pass}
    hikari:
      maximum-pool-size: 10          # at most 10 open DB connections
      pool-name: order-pool

  jpa:
    hibernate:
      ddl-auto: none                 # Hibernate must NEVER change the schema; Flyway owns it
    open-in-view: false              # don't keep a DB session open during the HTTP response;
                                     # forces all DB work into the service layer's transactions
    properties:
      hibernate:
        jdbc:
          time_zone: UTC             # store timestamps in UTC
          batch_size: 25             # batch inserts/updates for efficiency
        order_inserts: true
        order_updates: true

  flyway:
    enabled: true                    # run migrations on startup
    baseline-on-migrate: true        # tolerate a non-empty schema the first time

  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}

    producer:
      key-serializer: ...StringSerializer       # key = orderId as text
      value-serializer: ...JsonSerializer       # value = JSON, adds __TypeId__ header
      acks: all                                 # wait until the broker has durably stored it
      properties:
        enable.idempotence: true                # producer retries can't create duplicates in Kafka
        max.in.flight.requests.per.connection: 5  # max allowed while keeping idempotence + order
        delivery.timeout.ms: 120000

    consumer:
      group-id: order-service                   # fallback only; every listener sets its own groupId
      auto-offset-reset: earliest               # a brand-new group starts from the oldest message
      enable-auto-commit: false                 # Spring commits offsets, not a background timer
      key-deserializer: ...StringDeserializer
      value-deserializer: ...ErrorHandlingDeserializer   # safety wrapper (see 7.4)
      properties:
        spring.deserializer.value.delegate.class: ...JsonDeserializer  # the real deserializer
        spring.json.trusted.packages: com.oms.common.events            # only build our own classes
                                                                       # (security: no arbitrary types)
    listener:
      ack-mode: record               # commit after each record: a crash redelivers 1 message, not a batch
      concurrency: 3                 # 3 consumer threads = 1 per partition
      missing-topics-fatal: false    # don't crash if a topic doesn't exist yet at startup

server:
  port: 8081
  shutdown: graceful                 # finish in-flight work before stopping

management:
  endpoints.web.exposure.include: health,info,metrics   # Actuator endpoints exposed over HTTP
  endpoint.health.show-details: always

oms:                                 # this project's own settings
  outbox:
    poll-interval-ms: 500            # how often the outbox poller runs
    batch-size: 50                   # rows per poll
    max-attempts: 10                 # then park the row as FAILED
  kafka:
    retry:
      max-attempts: 3                # 1 delivery + 2 retries
      initial-interval-ms: 1000      # first wait 1 s
      multiplier: 2.0                # then 2 s, 4 s, ...
      max-interval-ms: 10000         # never wait more than 10 s
    topic:
      partitions: 3
      replicas: 1                    # 1 because there's only one broker locally
  saga:
    stuck-after: PT2M                # ISO-8601 duration: 2 minutes
    monitor-interval-ms: 30000
```

Service-specific settings:

| Property | Service | Default | Meaning |
| --- | --- | --- | --- |
| `oms.payment.auto-decline-above` | payment | 10000 | Orders above this are declined |
| `oms.simulation.failing-customer-id` | payment | CUST-BOOM | This customer triggers retries → DLT |
| `oms.simulation.failing-product-id` | inventory | P-BOOM | This product triggers retries → DLT (changeable at runtime) |
| `oms.outbox.send-timeout-ms` | all | 10000 | How long the publisher waits for a broker ack |

**Why `acks: all` + idempotent producer:** without them, a network blip could lose a message (acks=0/1)
or a producer retry could write it twice into Kafka.

**Why `concurrency: 3` with 3 partitions:** Kafka gives each partition to at most one consumer in a
group. More threads than partitions would sit idle.

---

## 14. Infrastructure: Docker, Compose, Kafka, Postgres

### 14.1 `docker-compose.yml`

Defines seven containers on one private network (`oms`):

| Container | Image | Notes |
| --- | --- | --- |
| `oms-postgres` | postgres:16-alpine | Volume `pgdata`; mounts the init SQL; health check `pg_isready` |
| `oms-kafka` | confluentinc/cp-kafka:7.7.1 | KRaft; volume `kafkadata`; health check lists topics |
| `oms-kafka-ui` | provectuslabs/kafka-ui | Browser UI on 8080 |
| `oms-order-service` … `oms-notification-service` | built from each `Dockerfile` | `depends_on` Postgres + Kafka being **healthy**; env vars override DB URL and Kafka address |

`depends_on: condition: service_healthy` means services don't start until Postgres and Kafka pass
their health checks — avoiding a burst of connection errors at startup.

`docker-compose.infra.yml` reuses the same definitions (`extends`) but only for Postgres, Kafka and
Kafka UI — for when you run the services from your IDE.

### 14.2 Kafka configuration explained

```yaml
KAFKA_PROCESS_ROLES: broker,controller      # KRaft: this single node is both broker and controller
KAFKA_LISTENERS: INTERNAL://0.0.0.0:19092,HOST://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093
KAFKA_ADVERTISED_LISTENERS: INTERNAL://kafka:19092,HOST://localhost:9092
KAFKA_AUTO_CREATE_TOPICS_ENABLE: "false"
*_REPLICATION_FACTOR: 1                     # only one broker exists
```

**Why two listeners?** A Kafka client first connects to a bootstrap address, then the broker tells it
"talk to me at address X" (the *advertised* address). Containers must use `kafka:19092` (Docker DNS
name), while a program on your laptop must use `localhost:9092`. One listener can't advertise both,
so there are two.

**Why topic auto-creation is off:** with it on, a typo like `order.replys` would silently create a
new empty topic and messages would go nowhere. Instead, topics are declared in `KafkaTopicsConfig`.

**KRaft** = Kafka's built-in consensus, replacing the separate ZooKeeper service.

### 14.3 The Dockerfiles (multi-stage build)

```dockerfile
FROM maven:3.9-eclipse-temurin-17 AS build    # Stage 1: a big image with Maven + JDK
COPY pom.xml + all module poms                # poms first...
RUN mvn ... dependency:go-offline             # ...so dependencies are cached as their own layer
COPY common-events/src, common-messaging/src, <service>/src
RUN mvn -pl <service> -am -DskipTests package # build this service and the modules it needs (-am)

FROM eclipse-temurin:17-jre-alpine            # Stage 2: a small runtime-only image
RUN adduser app ... ; USER app                # don't run as root (security)
COPY --from=build .../target/*.jar app.jar
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"       # JVM uses up to 75% of the container's memory limit
ENTRYPOINT java $JAVA_OPTS -jar /app/app.jar
```

**Why multi-stage:** the final image contains only a JRE and one jar — no Maven, no source, no JDK —
so it's smaller and has less attack surface.
**Why copy poms before sources:** Docker caches each layer. If you only change Java code, the slow
dependency download layer is reused.
**Why the build context is the repo root:** each service needs the parent POM and both common modules.

---

## 15. Running the project

### 15.1 Prerequisites

- JDK 17+ (`java -version`)
- Maven 3.9+ (`mvn -v`)
- Docker Desktop running, with Compose v2 (`docker compose version`)
- About 4 GB free RAM

### 15.2 Option A — everything in Docker (simplest)

```bash
docker compose up -d --build
docker compose ps                 # wait until all are "running"/"healthy"
```

The first build takes several minutes (it compiles all four services).

### 15.3 Option B — infrastructure in Docker, services from your IDE (best for development)

```bash
docker compose -f docker-compose.infra.yml up -d
mvn -DskipTests install           # builds and installs the common modules locally
```

Then, in four terminals (or run each `*Application` class from IntelliJ):

```bash
mvn -pl order-service        spring-boot:run
mvn -pl inventory-service    spring-boot:run
mvn -pl payment-service      spring-boot:run
mvn -pl notification-service spring-boot:run
```

You can set breakpoints in `OrderSagaOrchestrator.handle` and watch an order move through the saga.

> Don't run Option A and Option B at the same time — the ports would clash.

### 15.4 Check it's up

```powershell
Invoke-RestMethod http://localhost:8081/actuator/health     # status: UP
Invoke-RestMethod http://localhost:8082/api/inventory       # the 8 seeded products
```

### 15.5 Place an order by hand

PowerShell:

```powershell
$body = @{ customerId = "CUST-1001"; items = @(@{ productId = "P-LAPTOP"; quantity = 1; unitPrice = 1200.00 }) } | ConvertTo-Json -Depth 5
$o = Invoke-RestMethod -Method Post -Uri http://localhost:8081/api/orders -ContentType 'application/json' -Body $body
Start-Sleep 3
Invoke-RestMethod "http://localhost:8081/api/orders/$($o.orderId)"
```

curl (Git Bash / Linux / macOS):

```bash
curl -s -X POST http://localhost:8081/api/orders -H 'Content-Type: application/json' \
  -d '{"customerId":"CUST-1001","items":[{"productId":"P-LAPTOP","quantity":1,"unitPrice":1200.00}]}'
curl -s http://localhost:8081/api/orders/<orderId>
```

### 15.6 Smoke test — runs every scenario automatically

```powershell
./scripts/smoke-test.ps1
./scripts/smoke-test.ps1 -TimeoutSeconds 60   # slow machine / cold start
```

It checks all four services are healthy, resets the simulator, then runs and verifies:
1 happy path · 2 out-of-stock rejection · 3 decline + compensation (asserts stock really comes back) ·
4 full reverse chain · 5 retries + DLT (asserts the order correctly stays stuck) · 6 fix + replay
(asserts recovery and `pendingReplay = 0`). It prints `[pass]`/`[FAIL]` per scenario and exits
non-zero on failure.

It **polls** for the expected state rather than sleeping a fixed time: in an asynchronous system a
fixed sleep is a guess. The single fixed wait is scenario 5, which asserts something *doesn't*
happen — you can't poll for an absence.

### 15.7 Stopping and resetting

```bash
docker compose down        # stop, keep data
docker compose down -v     # stop AND delete volumes: fresh databases, empty Kafka,
                           # and the Postgres init script will run again next time
```

---

## 16. Testing

### 16.1 How tests are organised

- `mvn test` — runs unit tests with **Surefire** (no Docker needed). The repository currently has
  no unit tests, so this mainly compiles.
- `mvn verify` — also runs `*IT` integration tests with **Failsafe** (configured in
  `order-service/pom.xml`). These need Docker running.

```bash
mvn -pl order-service -am verify
```

### 16.2 What the integration tests do

`AbstractSagaIT` starts a **real Postgres 16** and a **real Kafka 7.7.1** with Testcontainers.
`@ServiceConnection` tells Spring Boot to point the app at those containers automatically.

Only **order-service** is started. The test **plays the participants by hand**: it reads the commands
order-service emits and sends back the replies a participant would send. This makes it possible to
send exactly the awkward messages a real system only produces occasionally — duplicates and
out-of-order replies.

A helper consumer (fresh group per test, reading from the start) collects messages, and tests claim
them by order id, so tests don't interfere with each other.

| Test | Proves |
| --- | --- |
| `happyPath` | ORDER_CREATED → reserve → payment (amount 500.00) → ORDER_CONFIRMED → CONFIRMED/COMPLETED |
| `duplicateReplyIsIgnored` | The same reply twice produces exactly **one** payment command |
| `paymentFailureReleasesInventory` | Decline → release command with the reason → CANCELLED/COMPENSATED |
| `inventoryRejectionNeedsNoCompensation` | Reject → CANCELLED/FAILED and **no** release or refund is sent |
| `fullCompensationChainRunsInReverse` | Refund is sent first, release is **not** sent until refund succeeds |
| `outOfPhaseReplyIsIgnored` | A payment reply while awaiting inventory changes nothing |

**Try this:** delete the `firstTimeSeeing` check in `OrderSagaOrchestrator.handle` and run the tests —
`duplicateReplyIsIgnored` fails because two payment commands are emitted. That's the double-charge bug
the pattern prevents.

---

## 17. API reference

### order-service — `http://localhost:8081`

| Method | Path | Body / params | Response |
| --- | --- | --- | --- |
| POST | `/api/orders` | `CreateOrderRequest` | **202** + `Location` header + `OrderResponse` |
| GET | `/api/orders/{orderId}` | — | `OrderResponse`, or **404** ProblemDetail |
| GET | `/api/orders?limit=50` | limit ≤ 200 | `OrderResponse[]`, newest first |
| GET | `/api/orders/status/summary` | — | `{ "AWAITING_INVENTORY": 0, ..., "COMPENSATION_FAILED": 0 }` |
| GET | `/api/ops/topics` | — | The six consumed topic names |
| GET | `/api/ops/dlt` | — | `{ "<topic>.DLT": { "total": n, "pendingReplay": n }, ... }` |
| GET | `/api/ops/dlt/{originTopic}?max=20` | origin topic name, not the `.DLT` name | `DltRecordView[]` (payload, exception type/message…) |
| POST | `/api/ops/dlt/{originTopic}/replay?max=20` | — | `{ fromTopic, toTopic, replayed, note }` |
| GET | `/api/ops/outbox` | — | `{ pending, sent, failed, recentFailures[] }` |
| GET | `/actuator/health` | — | `{ "status": "UP", ... }` |

`CreateOrderRequest`:

```json
{
  "customerId": "CUST-1001",
  "items": [ { "productId": "P-LAPTOP", "quantity": 1, "unitPrice": 1200.00 } ],
  "simulateConfirmFailure": false
}
```

`OrderResponse`:

```json
{
  "orderId": "1a2b3c4d-...",
  "customerId": "CUST-1001",
  "status": "CONFIRMED",
  "totalAmount": 1200.00,
  "failureReason": null,
  "sagaId": "b7f0...",
  "sagaState": "COMPLETED",
  "sagaStep": null,
  "createdAt": "2026-10-06T10:15:29.801Z",
  "updatedAt": "2026-10-06T10:15:31.412Z",
  "items": [ { "productId": "P-LAPTOP", "quantity": 1, "unitPrice": 1200.00 } ]
}
```

Validation error (400):

```json
{ "type": "about:blank", "title": "Bad Request", "status": 400,
  "detail": "customerId: customerId is required" }
```

### inventory-service — `http://localhost:8082`

| Method | Path | Notes |
| --- | --- | --- |
| GET | `/api/inventory` | All products: productId, availableQty, reservedQty, totalQty |
| GET | `/api/inventory/{productId}` | One product, or 404 |
| PUT | `/api/inventory/{productId}` | `{"availableQty": 50}` — set/create stock |
| GET | `/api/inventory/reservations/{orderId}` | Reservations for an order (`ACTIVE`/`RELEASED`) |
| GET | `/api/inventory/simulation` | Current failing product |
| PUT | `/api/inventory/simulation` | `{"failingProductId": ""}` disables; `"P-BOOM"` restores |

### payment-service — `http://localhost:8083`

| Method | Path | Notes |
| --- | --- | --- |
| GET | `/api/payments?limit=50` | Recent payments |
| GET | `/api/payments/order/{orderId}` | The payment for an order, or 404 |

### notification-service — `http://localhost:8084`

| Method | Path | Notes |
| --- | --- | --- |
| GET | `/api/notifications?limit=50` | Recent notifications |
| GET | `/api/notifications/order/{orderId}` | Notifications for an order, oldest first |

### Cheat sheet: which input triggers which path

| Input | Outcome |
| --- | --- |
| Any normal product, total ≤ 10,000 | `CONFIRMED / COMPLETED` |
| `P-OUT-OF-STOCK`, or quantity > stock, or unknown product | `CANCELLED / FAILED` |
| Total > 10,000 (e.g. 30 × P-MONITOR @ 400) | `CANCELLED / COMPENSATED` (stock released) |
| `"simulateConfirmFailure": true` | `CANCELLED / COMPENSATED` (refund, then release) |
| Product `P-BOOM` | Retries → `reserve-inventory.DLT`, order stuck `AWAITING_INVENTORY` |
| Customer `CUST-BOOM` | Retries → `process-payment.DLT`, order stuck `AWAITING_PAYMENT` |

---

## 18. Observing and debugging

### 18.1 Logs

```bash
docker compose logs -f order-service       # or inventory-service, payment-service, ...
```

Lines worth knowing:

| Log line | Meaning |
| --- | --- |
| `saga=... order=... started for customer ...` | An order was placed |
| `Published outbox ... -> <topic> key=...` | Outbox row reached Kafka |
| `saga=... state=AWAITING_INVENTORY <- RESERVE_INVENTORY SUCCEEDED` | Orchestrator received a reply |
| `Reserved {P-LAPTOP=1} for order ...` | Inventory reserved |
| `Rejecting reservation for order ...` | Business rejection (not an error) |
| `Authorised 1200.00 for order ... (AUTH-...)` / `Declined payment ...` | Payment outcome |
| `EMAIL to customer ...` | Notification rendered |
| `Skipping duplicate message ...` | Idempotency guard caught a duplicate |
| `Delivery attempt N failed for ...` | A retry is happening |
| `Routing ... to <topic>.DLT after exhausting retries` | Dead-lettered |
| `ignoring ... reply: state is ..., expected ...` | Out-of-phase reply ignored |
| `STUCK SAGA ...` | A saga hasn't moved for 2 minutes |
| `MANUAL INTERVENTION REQUIRED ...` | A compensation failed |

### 18.2 Kafka UI (http://localhost:8080)

Browse the topics, open messages (look at headers like `__TypeId__`, `X-Order-Id` and
`kafka_dlt-exception-message`), and view **consumer groups** with their **lag** (messages not yet
processed). Note each subscription has its own group, so you can tell a reservation backlog from a
release backlog.

### 18.3 Troubleshooting

| Symptom | Likely cause / fix |
| --- | --- |
| Service fails to start: connection refused to 5432/9092 | Infrastructure not running: `docker compose -f docker-compose.infra.yml up -d` |
| `password authentication failed for user order_user` | The Postgres volume pre-dates the init script. `docker compose down -v` and start again |
| Order stays `AWAITING_INVENTORY` | inventory-service down, or the command is in a DLT (`GET /api/ops/dlt`) |
| Order stays `AWAITING_PAYMENT` | payment-service down, or `CUST-BOOM` → check `process-payment.DLT` |
| `GET /api/ops/outbox` shows `pending` growing | Kafka unreachable from that service |
| Outbox rows `FAILED` | 10 publish attempts failed; read `lastError` |
| Port already in use | Option A and B running together, or another app on 8080–8084 |
| Smoke test fails on a cold start | Re-run with `-TimeoutSeconds 60` |
| Integration tests fail immediately | Docker isn't running (Testcontainers needs it) |

---

## 19. Design trade-offs and what is deliberately missing

### 19.1 Summary of decisions

| Decision | Chosen | Rejected | Why |
| --- | --- | --- | --- |
| Cross-service consistency | Saga | 2PC/XA | 2PC is slow, blocks on failures, not supported by Kafka+DB |
| Saga style | Orchestration | Choreography | Process visible in one class and one table |
| Publishing | Transactional outbox (polling) | Direct send; Debezium CDC | Direct send has the dual-write bug; CDC is more infrastructure than needed here |
| Delivery semantics | At-least-once + idempotent consumers | Exactly-once transactions | Simpler, works across DB + Kafka |
| Retry | Blocking `DefaultErrorHandler` | `@RetryableTopic` | Fewer moving parts; easy to switch later |
| Serialization | JSON with type header, shared module | Avro + Schema Registry | Simpler; fine while one team owns all services |
| Schema | Flyway | Hibernate `ddl-auto` | Reviewable, versioned, supports data migrations |
| Stock locking | Pessimistic, ordered | Optimistic | Clear business answer under contention; no deadlocks |
| API response for POST | 202 Accepted | 201 / blocking | The result isn't known yet; blocking would couple services |
| Stuck sagas | Detect and log | Auto-retry / auto-compensate | Automatic action risks double-charging |

### 19.2 Known simplifications / caveats

- `OutboxPublisher.publishPending` holds a DB transaction (and row locks) open while it waits for
  Kafka acks. Fine at this scale; under heavy load you'd claim → commit → publish → update
  separately (or use CDC).
- The JSON type header carries a full Java class name, tying consumers to the package structure
  (`spring.json.type.mapping` would decouple it).
- The ops endpoints have no authentication.
- Test hooks (`FailureSimulator`, `simulateConfirmFailure`, `CUST-BOOM`) would not exist in a
  real system.
- `processed_message` grows forever; production would purge old rows (there's an index on
  `processed_at` ready for that).

### 19.3 Deliberately not included (and when you'd add them)

| Missing | Adds | Worth it when |
| --- | --- | --- |
| API gateway | One entry point, auth, rate limits | Many services or external clients |
| Service discovery | No hard-coded hosts | Dynamic scaling (Kubernetes DNS covers most of it) |
| Distributed tracing (Micrometer + OpenTelemetry) | One timeline across services | Honestly, now — the most valuable next addition |
| Avro + Schema Registry | Enforced schema compatibility | Several teams produce events |
| Debezium CDC | Outbox without polling | High throughput |
| `@RetryableTopic` | Non-blocking retries | A poison message stalling a partition becomes a real problem |
| Kubernetes | Scaling, self-healing | Deploying beyond a laptop |

---

## 20. Glossary

| Term | Meaning |
| --- | --- |
| **202 Accepted** | HTTP status meaning "request received, processing not finished" |
| **ACID** | Atomic, Consistent, Isolated, Durable — guarantees of a database transaction |
| **Aggregate** | The main business object a message is about (here, an Order or a Saga) |
| **At-least-once** | Messages are never lost but may be duplicated |
| **Backoff (exponential)** | Waiting longer between each retry (1 s, 2 s, 4 s…) |
| **Bean** | An object created and managed by Spring |
| **Broker** | A Kafka server |
| **CDC (Change Data Capture)** | Reading a database's change log to publish events (e.g. Debezium) |
| **Command** | A message instructing one service to do something |
| **Compensation** | A new action that undoes an earlier committed step (refund, release) |
| **Consumer group** | Named group of consumers sharing a subscription; each group gets every message once |
| **DLT (dead-letter topic)** | Topic where messages that can't be processed are parked |
| **DTO** | Data Transfer Object — a class that shapes API input/output |
| **Dual write** | Writing to two systems without atomicity — the bug the outbox fixes |
| **Entity** | A Java class mapped to a database table (`@Entity`) |
| **Event** | A message stating a fact that already happened |
| **Eventual consistency** | The system becomes consistent after a short delay, not instantly |
| **Flyway** | Tool that applies versioned SQL migrations |
| **Idempotent** | Doing it twice has the same effect as once |
| **Idempotency ledger** | The `processed_message` table |
| **JPA / Hibernate** | Java standard / implementation for mapping objects to tables |
| **KRaft** | Kafka's built-in metadata consensus replacing ZooKeeper |
| **Key (Kafka)** | Value that decides a message's partition; here always `orderId` |
| **Lag** | How many messages a consumer group hasn't processed yet |
| **Listener** | A method annotated `@KafkaListener` that receives messages |
| **Offset** | A message's position in a partition |
| **Optimistic locking** | Detect conflicts at commit via a version number |
| **Orchestrator** | The component that drives a saga by sending commands and reading replies |
| **Outbox** | Table holding messages to be published, written in the business transaction |
| **Partition** | An ordered sub-log of a topic |
| **Pessimistic locking** | Lock the row before reading it (`SELECT ... FOR UPDATE`) |
| **Poison message** | A message that fails every time it's processed |
| **ProblemDetail** | Standard JSON error format (RFC 7807) |
| **Replay** | Re-sending dead-lettered messages to their original topic |
| **Reply** | A message reporting the outcome of a command |
| **Saga** | A business process made of local transactions plus compensations |
| **`SKIP LOCKED`** | Postgres option to skip rows locked by another transaction |
| **Testcontainers** | Library that runs real Docker containers inside tests |
| **Topic** | A named stream of messages in Kafka |
| **Transaction** | A group of DB changes that all succeed or all fail |

---

## 21. Suggested learning path and exercises

### Read the code in this order

1. `common-events/.../Topics.java` and the message records — learn the vocabulary.
2. `order-service/.../OrderService.java` — how a saga starts.
3. `common-messaging/.../OutboxRecorder.java` and `OutboxPublisher.java` — how messages leave.
4. `inventory-service/.../InventoryService.java` — a participant: idempotency, locking, replying.
5. `order-service/.../OrderSagaOrchestrator.java` — **the state machine**; read it slowly.
6. `payment-service/.../PaymentService.java` — the three layers against double-charging.
7. `common-messaging/.../IdempotencyGuard.java` and `config/KafkaErrorHandlingConfig.java`.
8. `order-service/.../ops/DltAdminService.java` — DLT replay.
9. `order-service/src/test/.../OrderSagaFlowIT.java` — how all of it is proven.

### Hands-on exercises

1. **Watch a saga live.** Run Option B, put a breakpoint in `OrderSagaOrchestrator.handle`, place an
   order, and step through each reply. Check `saga_instance` in psql between steps.
2. **Break idempotency on purpose.** Remove the `firstTimeSeeing` check in the orchestrator and run
   `mvn -pl order-service -am verify`. Read the failing test message. Put it back.
3. **See the outbox survive a Kafka outage.** `docker stop oms-kafka`, place an order (it still
   returns 202), check `GET /api/ops/outbox` (`pending` > 0), `docker start oms-kafka`, and watch the
   order complete.
4. **See a subscriber catch up.** Stop notification-service, place three orders (they all complete),
   start it again, and watch it process the backlog.
5. **Dead-letter and replay** using `P-BOOM` exactly as in [9.4](#94-technical-failure--retries-then-the-dead-letter-topic).
6. **Find a stuck saga.** Order as `CUST-BOOM`, wait 2 minutes, find `STUCK SAGA` in the logs and the
   saga row in `orderdb`.
7. **Extend the saga.** Add a `SHIP_ORDER` step handled by a new participant: new command and topic
   in `common-events`, a new saga state, a new branch in the orchestrator, and a compensation. This
   exercise touches every pattern in the project.

---

*If you understand Part 7 and Part 9.6, you understand the project. Everything else is the scaffolding
that lets those ideas run.*
