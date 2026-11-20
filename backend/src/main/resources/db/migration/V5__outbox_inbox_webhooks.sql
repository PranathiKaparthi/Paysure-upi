-- PaySure Phase 4: transactional outbox, consumer inbox, webhooks.

-- ---------------------------------------------------------------------------------------------
-- Transactional outbox. Rows are inserted in the SAME database transaction as the business change
-- they describe. A separate publisher reads them in `seq` order and publishes to Kafka (at-least-once).
-- ---------------------------------------------------------------------------------------------
CREATE TABLE outbox_events (
    seq             BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    id              UUID         NOT NULL,
    aggregate_type  VARCHAR(30)  NOT NULL,
    aggregate_id    UUID         NOT NULL,
    event_type      VARCHAR(50)  NOT NULL,
    topic           VARCHAR(80)  NOT NULL,
    payload         TEXT         NOT NULL,
    status          VARCHAR(12)  NOT NULL DEFAULT 'PENDING',
    attempts        INTEGER      NOT NULL DEFAULT 0,
    last_error      VARCHAR(300) NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ  NULL,
    CONSTRAINT uq_outbox_events_id UNIQUE (id),
    CONSTRAINT ck_outbox_status CHECK (status IN ('PENDING', 'PUBLISHED', 'DEAD')),
    CONSTRAINT ck_outbox_published CHECK ((status = 'PUBLISHED') = (published_at IS NOT NULL))
);

CREATE INDEX ix_outbox_pending ON outbox_events (seq) WHERE status = 'PENDING';
CREATE INDEX ix_outbox_aggregate ON outbox_events (aggregate_id, seq);

-- ---------------------------------------------------------------------------------------------
-- Inbox: (consumer, event id) is recorded in the same transaction as the consumer's effect, so a
-- redelivered Kafka record is recognised and ignored.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE inbox_events (
    consumer      VARCHAR(60)  NOT NULL,
    event_id      UUID         NOT NULL,
    event_type    VARCHAR(50)  NOT NULL,
    processed_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer, event_id)
);

-- ---------------------------------------------------------------------------------------------
-- Webhooks. Endpoint secrets are NOT stored: each is derived as HMAC(server key, endpoint id : version),
-- so a database dump alone does not reveal them. Rotating bumps `secret_version`.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE webhook_endpoints (
    id              UUID          PRIMARY KEY,
    owner_user_id   UUID          NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    url             VARCHAR(500)  NOT NULL,
    event_types     VARCHAR(300)  NOT NULL DEFAULT '*',
    description     VARCHAR(100)  NULL,
    enabled         BOOLEAN       NOT NULL DEFAULT TRUE,
    secret_version  INTEGER       NOT NULL DEFAULT 1,
    version         BIGINT        NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_webhook_endpoints_url CHECK (url ~ '^https?://')
);

CREATE INDEX ix_webhook_endpoints_owner ON webhook_endpoints (owner_user_id);

CREATE TABLE webhook_deliveries (
    id                UUID          PRIMARY KEY,
    endpoint_id       UUID          NOT NULL REFERENCES webhook_endpoints (id) ON DELETE RESTRICT,
    event_id          UUID          NOT NULL,
    event_type        VARCHAR(50)   NOT NULL,
    payload           TEXT          NOT NULL,
    status            VARCHAR(12)   NOT NULL DEFAULT 'PENDING',
    attempts          INTEGER       NOT NULL DEFAULT 0,
    max_attempts      INTEGER       NOT NULL,
    next_attempt_at   TIMESTAMPTZ   NOT NULL,
    lease_until       TIMESTAMPTZ   NULL,
    last_status_code  INTEGER       NULL,
    last_error        VARCHAR(300)  NULL,
    last_attempt_at   TIMESTAMPTZ   NULL,
    delivered_at      TIMESTAMPTZ   NULL,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    -- One delivery per (endpoint, event): consumer redelivery can never create a second one.
    CONSTRAINT uq_webhook_delivery_event UNIQUE (endpoint_id, event_id),
    CONSTRAINT ck_webhook_delivery_status CHECK (status IN ('PENDING', 'DELIVERING', 'RETRYING', 'SUCCESS', 'DEAD')),
    CONSTRAINT ck_webhook_delivery_attempts CHECK (attempts >= 0 AND max_attempts >= 1),
    CONSTRAINT ck_webhook_delivery_success CHECK ((status = 'SUCCESS') = (delivered_at IS NOT NULL))
);

CREATE INDEX ix_webhook_deliveries_due ON webhook_deliveries (next_attempt_at) WHERE status IN ('PENDING', 'RETRYING', 'DELIVERING');
CREATE INDEX ix_webhook_deliveries_endpoint ON webhook_deliveries (endpoint_id, created_at DESC);
CREATE INDEX ix_webhook_deliveries_created ON webhook_deliveries (created_at DESC);

CREATE TABLE webhook_delivery_attempts (
    id           BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    delivery_id  UUID         NOT NULL REFERENCES webhook_deliveries (id) ON DELETE RESTRICT,
    attempt_no   INTEGER      NOT NULL,
    started_at   TIMESTAMPTZ  NOT NULL,
    duration_ms  BIGINT       NOT NULL,
    status_code  INTEGER      NULL,
    error        VARCHAR(300) NULL,
    outcome      VARCHAR(12)  NOT NULL,
    CONSTRAINT ck_webhook_attempt_outcome CHECK (outcome IN ('SUCCESS', 'RETRY', 'DEAD'))
);

CREATE INDEX ix_webhook_attempts_delivery ON webhook_delivery_attempts (delivery_id, attempt_no);

-- ---------------------------------------------------------------------------------------------
-- Built-in sandbox webhook receiver (local demonstration). `processed_at` is set at most once per
-- (endpoint, event): that is the "business processing" the receiver performs exactly once.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE webhook_receipts (
    endpoint_id   UUID         NOT NULL,
    event_id      UUID         NOT NULL,
    event_type    VARCHAR(50)  NOT NULL,
    calls         INTEGER      NOT NULL DEFAULT 1,
    first_seen    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_seen     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    processed_at  TIMESTAMPTZ  NULL,
    last_result   VARCHAR(30)  NOT NULL,
    PRIMARY KEY (endpoint_id, event_id)
);
