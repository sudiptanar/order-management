-- orderdb: owned by order-service. No other service may read these tables.

CREATE TABLE orders (
    id                       UUID         PRIMARY KEY,
    customer_id              VARCHAR(64)  NOT NULL,
    status                   VARCHAR(32)  NOT NULL,
    total_amount             NUMERIC(19,2) NOT NULL,
    failure_reason           VARCHAR(512),
    simulate_confirm_failure BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at               TIMESTAMPTZ  NOT NULL,
    updated_at               TIMESTAMPTZ  NOT NULL,
    version                  BIGINT       NOT NULL DEFAULT 0
);

CREATE INDEX idx_orders_status ON orders (status);
CREATE INDEX idx_orders_created_at ON orders (created_at DESC);

CREATE TABLE order_items (
    id         UUID          PRIMARY KEY,
    order_id   UUID          NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    product_id VARCHAR(64)   NOT NULL,
    quantity   INTEGER       NOT NULL CHECK (quantity > 0),
    unit_price NUMERIC(19,2) NOT NULL CHECK (unit_price >= 0)
);

CREATE INDEX idx_order_items_order_id ON order_items (order_id);

-- The orchestrator's state, one row per saga. This table is what makes "why is order 42 stuck?"
-- answerable with a single query instead of a trawl through four services' logs.
CREATE TABLE saga_instance (
    saga_id        UUID         PRIMARY KEY,
    order_id       UUID         NOT NULL UNIQUE REFERENCES orders (id) ON DELETE CASCADE,
    state          VARCHAR(48)  NOT NULL,
    current_step   VARCHAR(48),
    failure_reason VARCHAR(512),
    created_at     TIMESTAMPTZ  NOT NULL,
    updated_at     TIMESTAMPTZ  NOT NULL,
    version        BIGINT       NOT NULL DEFAULT 0
);

-- Supports the stuck-saga monitor.
CREATE INDEX idx_saga_state_updated ON saga_instance (state, updated_at);
-- ---------------------------------------------------------------------------------------------
-- Transactional outbox. Written inside the business transaction; drained to Kafka by a poller.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE outbox (
    id             UUID         PRIMARY KEY,
    aggregate_type VARCHAR(64)  NOT NULL,
    aggregate_id   VARCHAR(64)  NOT NULL,
    topic          VARCHAR(128) NOT NULL,
    message_key    VARCHAR(128),
    payload_type   VARCHAR(255) NOT NULL,
    payload        TEXT         NOT NULL,
    status         VARCHAR(16)  NOT NULL,
    attempts       INTEGER      NOT NULL DEFAULT 0,
    last_error     VARCHAR(1024),
    created_at     TIMESTAMPTZ  NOT NULL,
    published_at   TIMESTAMPTZ
);

-- The poller queries PENDING in insert order, so index exactly that.
CREATE INDEX idx_outbox_pending ON outbox (status, created_at);

-- ---------------------------------------------------------------------------------------------
-- Idempotency ledger. The composite primary key is the actual mechanism: it makes a second
-- attempt to record the same (message, consumer group) fail at the database level, which is the
-- only place a guarantee like this can be enforced.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE processed_message (
    message_id     UUID         NOT NULL,
    consumer_group VARCHAR(64)  NOT NULL,
    topic          VARCHAR(128),
    processed_at   TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (message_id, consumer_group)
);

CREATE INDEX idx_processed_message_processed_at ON processed_message (processed_at);