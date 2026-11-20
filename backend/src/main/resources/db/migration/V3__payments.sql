-- PaySure Phase 2: payments, idempotency, state history, collect requests and the simulated bank.

-- Generic append-only guard (used by audit/history tables).
CREATE FUNCTION reject_mutation() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'table % is append-only (% rejected)', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$;

ALTER TABLE ledger_journals DROP CONSTRAINT ck_ledger_journals_type;
ALTER TABLE ledger_journals ADD CONSTRAINT ck_ledger_journals_type
    CHECK (journal_type IN ('SANDBOX_FUNDING', 'SANDBOX_TOP_UP', 'PAYMENT_TRANSFER'));

-- ---------------------------------------------------------------------------------------------
-- Payments
-- hold_status tracks the reserved-funds lifecycle so a hold is captured or released exactly once.
-- The CHECK constraints below make illegal status/hold combinations impossible to persist.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE payments (
    id                  UUID         PRIMARY KEY,
    payer_user_id       UUID         NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    payer_account_id    UUID         NOT NULL REFERENCES accounts (id) ON DELETE RESTRICT,
    payer_vpa           VARCHAR(64)  NOT NULL,
    payee_user_id       UUID         NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    payee_account_id    UUID         NOT NULL REFERENCES accounts (id) ON DELETE RESTRICT,
    payee_vpa           VARCHAR(64)  NOT NULL,
    amount_paise        BIGINT       NOT NULL,
    note                VARCHAR(100) NULL,
    status              VARCHAR(20)  NOT NULL,
    hold_status         VARCHAR(20)  NOT NULL,
    failure_code        VARCHAR(40)  NULL,
    failure_reason      VARCHAR(200) NULL,
    collect_request_id  UUID         NULL,
    version             BIGINT       NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at        TIMESTAMPTZ  NULL,
    CONSTRAINT ck_payments_amount CHECK (amount_paise > 0),
    CONSTRAINT ck_payments_distinct_accounts CHECK (payer_account_id <> payee_account_id),
    CONSTRAINT ck_payments_status CHECK (status IN ('CREATED', 'PROCESSING', 'PENDING', 'SUCCESS', 'FAILED')),
    CONSTRAINT ck_payments_hold CHECK (hold_status IN ('NONE', 'HELD', 'CAPTURED', 'RELEASED')),
    CONSTRAINT ck_payments_completed CHECK ((status IN ('SUCCESS', 'FAILED')) = (completed_at IS NOT NULL)),
    CONSTRAINT ck_payments_created_hold CHECK (status <> 'CREATED' OR hold_status = 'NONE'),
    CONSTRAINT ck_payments_inflight_hold CHECK (status NOT IN ('PROCESSING', 'PENDING') OR hold_status = 'HELD'),
    CONSTRAINT ck_payments_success_hold CHECK (status <> 'SUCCESS' OR hold_status = 'CAPTURED'),
    CONSTRAINT ck_payments_failed_hold CHECK (status <> 'FAILED' OR hold_status IN ('NONE', 'RELEASED'))
);

CREATE INDEX ix_payments_payer ON payments (payer_user_id, created_at DESC);
CREATE INDEX ix_payments_payee ON payments (payee_user_id, created_at DESC);
CREATE INDEX ix_payments_status_updated ON payments (status, updated_at);
CREATE INDEX ix_payments_payer_account_hold ON payments (payer_account_id) WHERE hold_status = 'HELD';

-- Durable processing instruction: written BEFORE the bank is called. The bank_reference is the
-- stable business reference used for submission, status lookups and callbacks.
CREATE TABLE payment_attempts (
    id                   UUID         PRIMARY KEY,
    payment_id           UUID         NOT NULL REFERENCES payments (id) ON DELETE RESTRICT,
    attempt_no           INTEGER      NOT NULL,
    bank_reference       VARCHAR(40)  NOT NULL,
    scenario             VARCHAR(30)  NULL,
    delay_seconds        INTEGER      NOT NULL DEFAULT 0,
    duplicate_callbacks  INTEGER      NOT NULL DEFAULT 1,
    submitted_at         TIMESTAMPTZ  NULL,
    response_kind        VARCHAR(20)  NULL,
    response_at          TIMESTAMPTZ  NULL,
    last_callback_seq    INTEGER      NOT NULL DEFAULT 0,
    callbacks_received   INTEGER      NOT NULL DEFAULT 0,
    callbacks_ignored    INTEGER      NOT NULL DEFAULT 0,
    version              BIGINT       NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_payment_attempts_reference UNIQUE (bank_reference),
    CONSTRAINT uq_payment_attempts_no UNIQUE (payment_id, attempt_no),
    CONSTRAINT ck_payment_attempts_counts CHECK (attempt_no >= 1 AND delay_seconds >= 0 AND duplicate_callbacks >= 1)
);

CREATE TABLE payment_state_history (
    id           BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    payment_id   UUID         NOT NULL REFERENCES payments (id) ON DELETE RESTRICT,
    from_status  VARCHAR(20)  NULL,
    to_status    VARCHAR(20)  NOT NULL,
    source       VARCHAR(30)  NOT NULL,
    reason       VARCHAR(200) NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX ix_payment_state_history_payment ON payment_state_history (payment_id, id);

CREATE TRIGGER trg_payment_state_history_immutable
    BEFORE UPDATE OR DELETE ON payment_state_history
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();

-- Idempotency: one row per (caller, key), committed atomically with the payment it created.
-- The payment FK is deferred because the key is claimed before the payment row is inserted.
CREATE TABLE idempotency_records (
    id               UUID         PRIMARY KEY,
    caller_user_id   UUID         NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    idempotency_key  VARCHAR(100) NOT NULL,
    request_hash     CHAR(64)     NOT NULL,
    payment_id       UUID         NOT NULL REFERENCES payments (id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at       TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_idempotency_caller_key UNIQUE (caller_user_id, idempotency_key)
);

CREATE INDEX ix_idempotency_expires ON idempotency_records (expires_at);

-- ---------------------------------------------------------------------------------------------
-- Collect requests ("request money"). At most one payment can ever fulfil a request.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE collect_requests (
    id                    UUID         PRIMARY KEY,
    requester_user_id     UUID         NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    requester_account_id  UUID         NOT NULL REFERENCES accounts (id) ON DELETE RESTRICT,
    requester_vpa         VARCHAR(64)  NOT NULL,
    payer_user_id         UUID         NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    payer_account_id      UUID         NOT NULL REFERENCES accounts (id) ON DELETE RESTRICT,
    payer_vpa             VARCHAR(64)  NOT NULL,
    amount_paise          BIGINT       NOT NULL,
    note                  VARCHAR(100) NULL,
    status                VARCHAR(20)  NOT NULL,
    payment_id            UUID         NULL REFERENCES payments (id) ON DELETE RESTRICT,
    expires_at            TIMESTAMPTZ  NOT NULL,
    decided_at            TIMESTAMPTZ  NULL,
    version               BIGINT       NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_collect_amount CHECK (amount_paise > 0),
    CONSTRAINT ck_collect_distinct_accounts CHECK (requester_account_id <> payer_account_id),
    CONSTRAINT ck_collect_status CHECK (status IN ('PENDING', 'APPROVED', 'DECLINED', 'CANCELLED', 'EXPIRED'))
);

CREATE INDEX ix_collect_payer ON collect_requests (payer_user_id, created_at DESC);
CREATE INDEX ix_collect_requester ON collect_requests (requester_user_id, created_at DESC);

ALTER TABLE payments ADD CONSTRAINT fk_payments_collect_request
    FOREIGN KEY (collect_request_id) REFERENCES collect_requests (id) ON DELETE RESTRICT;
CREATE UNIQUE INDEX uq_payments_collect_request ON payments (collect_request_id) WHERE collect_request_id IS NOT NULL;

-- ---------------------------------------------------------------------------------------------
-- Simulated bank (a stand-in for an external system; deliberately no FKs into PaySure tables).
-- committed_status is what the bank ACTUALLY did; response_kind is what it TOLD the caller.
-- They can differ, e.g. committed SUCCESS but response TIMEOUT (lost acknowledgement).
-- ---------------------------------------------------------------------------------------------
CREATE TABLE sim_bank_transfers (
    bank_reference    VARCHAR(40)  PRIMARY KEY,
    payer_account_id  UUID         NOT NULL,
    payee_account_id  UUID         NOT NULL,
    amount_paise      BIGINT       NOT NULL,
    scenario          VARCHAR(30)  NOT NULL,
    committed_status  VARCHAR(20)  NOT NULL,
    failure_reason    VARCHAR(100) NULL,
    response_kind     VARCHAR(20)  NOT NULL,
    resolves_at       TIMESTAMPTZ  NULL,
    committed_at      TIMESTAMPTZ  NULL,
    version           BIGINT       NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_sim_bank_status CHECK (committed_status IN ('PROCESSING', 'SUCCESS', 'FAILED')),
    CONSTRAINT ck_sim_bank_amount CHECK (amount_paise > 0)
);

CREATE INDEX ix_sim_bank_transfers_due ON sim_bank_transfers (resolves_at) WHERE committed_status = 'PROCESSING';

CREATE TABLE sim_bank_callbacks (
    id                 UUID         PRIMARY KEY,
    bank_reference     VARCHAR(40)  NOT NULL REFERENCES sim_bank_transfers (bank_reference) ON DELETE RESTRICT,
    sequence           INTEGER      NOT NULL,
    reported_status    VARCHAR(20)  NOT NULL,
    deliver_at         TIMESTAMPTZ  NOT NULL,
    delivered_at       TIMESTAMPTZ  NULL,
    delivery_attempts  INTEGER      NOT NULL DEFAULT 0,
    last_error         VARCHAR(200) NULL,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_sim_bank_callback_status CHECK (reported_status IN ('PROCESSING', 'SUCCESS', 'FAILED'))
);

CREATE INDEX ix_sim_bank_callbacks_due ON sim_bank_callbacks (deliver_at) WHERE delivered_at IS NULL;
