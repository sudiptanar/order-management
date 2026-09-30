-- paymentdb: owned by payment-service.

CREATE TABLE payment (
    id                UUID          PRIMARY KEY,
    -- UNIQUE is the real defence against double-charging. The idempotency ledger and the
    -- application checks sit in front of it; this holds even if both are wrong.
    order_id          UUID          NOT NULL UNIQUE,
    customer_id       VARCHAR(64)   NOT NULL,
    amount            NUMERIC(19,2) NOT NULL CHECK (amount >= 0),
    status            VARCHAR(16)   NOT NULL,
    authorization_ref VARCHAR(64),
    failure_reason    VARCHAR(512),
    created_at        TIMESTAMPTZ   NOT NULL,
    updated_at        TIMESTAMPTZ   NOT NULL,
    version           BIGINT        NOT NULL DEFAULT 0
);

CREATE INDEX idx_payment_customer ON payment (customer_id);
CREATE INDEX idx_payment_created_at ON payment (created_at DESC);
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