-- inventorydb: owned by inventory-service.

-- Stock is split into available and reserved rather than tracked as one number, so that a
-- reservation can be undone exactly. available + reserved is the physical count.
CREATE TABLE inventory_item (
    product_id    VARCHAR(64) PRIMARY KEY,
    available_qty INTEGER     NOT NULL CHECK (available_qty >= 0),
    reserved_qty  INTEGER     NOT NULL DEFAULT 0 CHECK (reserved_qty >= 0),
    version       BIGINT      NOT NULL DEFAULT 0
);

-- What was held for one order, so compensation knows exactly what to give back instead of
-- trusting the release command to restate the quantities.
CREATE TABLE inventory_reservation (
    id          UUID        PRIMARY KEY,
    order_id    UUID        NOT NULL,
    product_id  VARCHAR(64) NOT NULL REFERENCES inventory_item (product_id),
    quantity    INTEGER     NOT NULL CHECK (quantity > 0),
    status      VARCHAR(16) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL,
    released_at TIMESTAMPTZ,
    -- One reservation row per product per order. Makes a double reservation impossible even if
    -- the application-level checks are bypassed.
    CONSTRAINT uq_reservation_order_product UNIQUE (order_id, product_id)
);

CREATE INDEX idx_reservation_order ON inventory_reservation (order_id, status);
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