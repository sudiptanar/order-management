-- notificationdb: owned by notification-service.

CREATE TABLE notification (
    id          UUID         PRIMARY KEY,
    order_id    UUID         NOT NULL,
    customer_id VARCHAR(64)  NOT NULL,
    channel     VARCHAR(16)  NOT NULL,
    event_type  VARCHAR(32)  NOT NULL,
    subject     VARCHAR(256) NOT NULL,
    body        TEXT         NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_notification_order ON notification (order_id, created_at);
CREATE INDEX idx_notification_created_at ON notification (created_at DESC);

-- ---------------------------------------------------------------------------------------------
-- This service publishes nothing, so its outbox stays empty. The table exists anyway because the
-- shared OutboxPublisher bean queries it on every poll - and an empty table is cheaper to keep
-- than a conditional bean is to explain.
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

CREATE TABLE processed_message (
    message_id     UUID         NOT NULL,
    consumer_group VARCHAR(64)  NOT NULL,
    topic          VARCHAR(128),
    processed_at   TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (message_id, consumer_group)
);

CREATE INDEX idx_processed_message_processed_at ON processed_message (processed_at);