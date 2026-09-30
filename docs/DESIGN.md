# Design notes

Why each decision went the way it did - including the alternatives that were rejected, which is
usually the more interesting half.

---

## 1. Orchestration rather than choreography

Both are valid sagas. Choreography looks simpler at first: each service listens for events and
reacts, with no coordinator. The cost shows up later.

- The business process exists only as an emergent property of four independent subscriptions. No
  single file tells you the order of the steps.
- Compensation logic is smeared across every participant. "What happens if payment fails?" requires
  reading `inventory-service` to find out.
- Answering "why is order 42 stuck?" means correlating four services' logs.
- Adding a step means editing services that should not have to care.

Orchestration puts the sequence in one state machine (`OrderSagaOrchestrator`) and one table
(`saga_instance`). The trade is real - the orchestrator knows about every participant, and it is a
single point of failure for the workflow - but for a system a person has to operate, an explicit
process you can query beats an implicit one you have to reconstruct.

**When choreography wins:** when steps genuinely do not need ordering, or when the services belong
to teams that must not have a shared coordinator.

## 2. No distributed transaction

There is no two-phase commit and no XA. Each step commits locally and immediately.

That means a partially-completed saga is a real, visible state - an order can be paid for while not
yet confirmed. The system is eventually consistent by construction, and the API reflects it:
`POST /api/orders` returns **202 Accepted**, not 201. Returning a final answer would mean blocking
on three services, which is exactly the coupling this design exists to avoid.

Compensation is a *new business operation*, never a rollback. A refund is not the absence of a
payment - the order's history records both, which is what an auditor and a customer both need.

## 3. Transactional outbox

The naive version of publishing an event:

```java
orderRepository.save(order);      // commit 1: Postgres
kafkaTemplate.send(event);        // commit 2: Kafka
```

Two commits to two systems with no atomicity between them. Crash in the gap and the order exists
while the event never happened - silently, with no error recorded anywhere. Reverse the order and
you get the opposite failure: an event for an order that does not exist.

So nothing is sent directly. `OutboxRecorder.record(...)` writes the intent to the `outbox` table
inside the business transaction; `OutboxPublisher` polls and publishes. `Propagation.MANDATORY` on
the recorder makes calling it outside a transaction a loud failure rather than a quiet
reintroduction of the bug.

The price is at-least-once publishing: a crash after the send but before the status update
republishes the message. That is acceptable precisely because the consumers are idempotent - the
two patterns only work as a pair.

**Production upgrade:** Debezium reading the Postgres WAL instead of polling. Same guarantees,
no poll interval, no query load. Worth it at volume; a lot of infrastructure to explain at this size.

**Multiple replicas:** `claimPendingBatch` uses `FOR UPDATE SKIP LOCKED`, so several instances can
poll the same table without publishing the same row twice.

**A caveat worth naming:** `publishPending` is `@Transactional` and blocks on the broker ack inside
that transaction, so a database transaction and its row locks stay open across a network call. At
this scale that is fine, and it buys something real - a row is only marked `SENT` after the broker
has confirmed it. Under load it is the wrong shape, and the fix is to decouple the two: claim a
batch and commit, publish outside any transaction, then commit the status updates separately. That
widens the duplicate window, which is again absorbed by the consumers being idempotent. CDC removes
the question entirely.

## 4. Idempotent consumers

Kafka delivers at-least-once. Duplicates are not an edge case - they are the normal consequence of a
consumer group rebalance, a failed offset commit, or an outbox republish. Every consumer must assume
it will see the same message more than once.

`processed_message` is keyed by `(message_id, consumer_group)`:

- **Composite, not just `message_id`:** two services consuming the same event must each get their
  own chance to process it.
- **Written in the business transaction:** if the handler throws, the ledger row rolls back with it
  and the redelivery is free to retry. A separate transaction would let the ledger record work that
  never happened.
- **The primary key is the real guarantee.** The `existsById` check in front of it is an
  optimisation. If two consumers race past it, the database rejects the loser, its transaction rolls
  back, and the redelivery then finds the committed row and skips.

There are three layers of defence against double-charging, deliberately:

1. `processed_message` - catches the same message twice.
2. `payment.findByOrderId` - catches a *different* message asking for the same thing, which happens
   when an orchestrator times out and reissues a command with a fresh id.
3. `UNIQUE (order_id)` on `payment` - holds even if both checks above are wrong.

Only the third is a guarantee. The first two are there so the third never has to fire.

## 5. Retries and dead-letter topics

One `CommonErrorHandler` bean per service (`KafkaErrorHandlingConfig`), which Spring Boot wires into
every listener container.

The distinction that matters is **retryable versus not**:

| Kind | Example | Treatment |
| --- | --- | --- |
| Transient | database briefly unreachable, lock timeout | Exponential backoff, 3 attempts |
| Fatal | payload does not deserialize, unknown aggregate | Straight to `<topic>.DLT`, no retries |

Retrying a malformed message three times accomplishes nothing and delays every message queued
behind it on that partition. `NonRetryableMessageException` and `DeserializationException` skip the
backoff entirely.

**A business rejection is neither.** When stock runs out, `inventory-service` replies `FAILED` - it
does not throw. Throwing would dead-letter a message that was handled perfectly correctly. This is
the most common mistake in saga participants: conflating "the answer is no" with "I could not
answer".

**Blocking versus non-blocking retry.** This uses `DefaultErrorHandler`, whose backoff pauses the
consumer - so one poison message holds up its partition for the duration. The alternative is
`@RetryableTopic`, which forwards failures to timed retry topics and keeps the main partition
moving. Non-blocking is the better answer under load; blocking is fewer moving parts to understand,
which is why it is here. Switching is a change to one `@Bean`.

**`ErrorHandlingDeserializer`** wraps the JSON deserializer. Without it, a malformed payload throws
inside the consumer's poll loop, which no error handler can catch - the container dies and restarts
onto the same bad message, forever. With it, the failure arrives as a record the handler can
dead-letter.

**Two producers.** The dead-letter publisher needs a `byte[]` template as well as the JSON one:
when a message fails because it could not be *deserialized*, there is no object to re-serialize,
only the original bytes. Handing those to a JSON serializer would base64 them and destroy the
evidence you opened the DLT to read.

**DLT partitioning.** The recoverer targets partition `-1` (let Kafka choose) rather than mirroring
the source partition, which breaks as soon as a DLT has fewer partitions than the topic it shadows.

## 6. Consumer groups, one per subscription

Every listener declares its own `groupId`, and the constant is shared with the idempotency
ledger key so the two cannot drift:

```java
public static final String GROUP_RESERVE = "inventory-service.reserve-inventory";

@KafkaListener(topics = Topics.RESERVE_INVENTORY, groupId = InventoryService.GROUP_RESERVE)
```

Two reasons it is explicit rather than inherited from `spring.kafka.consumer.group-id`:

- **`id` silently becomes `group.id`.** A `@KafkaListener` with an `id` and no `groupId` uses the
  id as its consumer group. That is easy to miss, and it makes the `consumer_group` column in
  `processed_message` record a group name Kafka has never heard of - so the ledger looks right while
  describing something that does not exist.
- **Separate groups keep the diagnostics separate.** Kafka can handle one group whose members
  subscribe to different topics, but the subscriptions then share a rebalance and their offsets and
  lag are reported together. A backlog of reservations becomes indistinguishable from a backlog of
  releases, which is precisely the question you are asking when you go looking.

## 7. Building Kafka clients by hand

`KafkaProperties.buildProducerProperties()` returns only what is in `application.yml`. When the
broker address is supplied at *runtime* - Testcontainers with `@ServiceConnection`, or a Docker
Compose service connection - Spring Boot expresses it as a `KafkaConnectionDetails` bean and
applies it only to the factories it auto-configures itself.

So any client assembled from `KafkaProperties` directly misses it and connects to whatever
`application.yml` said, while the rest of the application talks to the real broker. The symptom is
thoroughly misleading: the producer reports `Topic order.events not present in metadata after
60000 ms`, which reads like a topic-creation problem and is actually a wrong-address problem.

`KafkaConnectionConfigSupport` exists to apply it, and both the producer factories and the
DLT admin clients route their config through it.

## 8. Saga timeouts

A participant can crash after consuming a command and before replying. No message will ever arrive,
so the state machine has nothing to act on and the saga sits in `AWAITING_*` forever.

`StuckSagaMonitor` finds these and logs them. It deliberately does **not** act: resending the
command, forcing compensation, or paging someone is a business decision, and making it automatically
without that decision is how you double-charge customers.

`COMPENSATION_FAILED` is a separate state for when a compensation itself fails. There is no
automated answer - money or stock is stranded and a human has to look. Alert on it.

## 9. Message ordering

Every message is keyed by `orderId`, so all messages for one order land on the same partition and
are consumed in order. Across orders there is no ordering guarantee, and none is needed.

`listener.concurrency: 3` with 3 partitions gives one consumer thread per partition. Raising
concurrency above the partition count just leaves threads idle.

The orchestrator does not *depend* on ordering, though - `isOutOfPhase` checks that a reply matches
the step actually being awaited, and ignores it otherwise. Ignoring rather than throwing is correct:
a late reply is normal traffic, and dead-lettering it would be noise.

## 10. Serialization

JSON via `JsonSerializer`/`JsonDeserializer`, with the payload type carried in a Kafka header. That
is what lets `inventory-service` consume two different command types through one container factory.

The shared `common-events` module is a coupling compromise. Production alternatives, in increasing
order of rigour: duplicate the DTOs per service; or Avro plus Schema Registry with enforced
compatibility rules. The header currently carries a fully-qualified class name, which ties consumers
to a package structure; `spring.json.type.mapping` replaces it with logical names when that starts
to hurt.

Broker-side topic auto-creation is switched **off**. It hides typos by silently creating
`order.replys` and then never delivering anything. Topics are declared in `KafkaTopicsConfig`, and
`KafkaAdmin` creates only what is missing.

## 11. Database per service

One Postgres container, four databases, four owners with separate passwords. Logical isolation is
what the pattern is about - no shared tables, no cross-service joins - and separate credentials make
violating it impossible rather than merely discouraged. Four *containers* would cost RAM and teach
nothing extra.

Flyway owns every schema; `ddl-auto` is `none` everywhere. `ddl-auto: update` is convenient right
up to the first production deployment, and then it is a liability - it cannot express a data
migration, and nobody can review what it is about to do.

Pessimistic locking on `inventory_item` is deliberate. Optimistic locking would be wrong: two
concurrent orders for the last unit would both read "1 available", both pass the check, and one
would fail at commit with a version conflict rather than a clear "insufficient stock". Taking the
row lock before the check makes read-then-write atomic. Lock acquisition is ordered by product id,
because inconsistent lock ordering is how two concurrent orders deadlock each other.

## 12. What is missing, and when it starts to matter

| Left out | What it would add | Worth it when |
| --- | --- | --- |
| API gateway | One entry point, shared auth, rate limits | More than a handful of services, or external clients |
| Service discovery | No hardcoded hosts | Dynamic scaling; Kubernetes DNS covers most of it |
| Distributed tracing | One timeline across four services | Now, honestly - the cheapest real upgrade here |
| Avro + Schema Registry | Enforced schema compatibility | More than one team produces events |
| Debezium CDC | Outbox without polling | Sustained high throughput |
| `@RetryableTopic` | Non-blocking retries | A poison message stalling a partition becomes a real problem |
| Kubernetes | Real orchestration, scaling, health management | Deploying somewhere other than a laptop |

The most valuable next addition is Micrometer tracing with an OpenTelemetry exporter. Everything
else on that list is infrastructure; tracing changes what you can *see*, which is the thing this
architecture costs you most.