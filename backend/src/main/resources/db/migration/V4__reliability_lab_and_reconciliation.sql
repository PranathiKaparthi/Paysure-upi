-- PaySure Phase 3: Reliability Lab, self-healing reconciliation, reliability score.

-- ---------------------------------------------------------------------------------------------
-- Reconciliation bookkeeping on payments. Managed with plain SQL by the reconciliation worker.
-- A lease (owner + expiry) lets several workers run concurrently without touching the same payment;
-- a crashed worker's lease simply expires.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE payments
    ADD COLUMN recon_lease_owner     UUID         NULL,
    ADD COLUMN recon_lease_until     TIMESTAMPTZ  NULL,
    ADD COLUMN recon_attempts        INTEGER      NOT NULL DEFAULT 0,
    ADD COLUMN last_reconciled_at    TIMESTAMPTZ  NULL,
    ADD COLUMN needs_manual_review   BOOLEAN      NOT NULL DEFAULT FALSE,
    ADD COLUMN manual_review_reason  VARCHAR(200) NULL;

CREATE INDEX ix_payments_inflight ON payments (updated_at) WHERE status IN ('PROCESSING', 'PENDING');
CREATE INDEX ix_payments_manual_review ON payments (updated_at) WHERE needs_manual_review;

-- Idempotent replays are stored (not only counted in memory) so the reliability score can use them.
ALTER TABLE idempotency_records
    ADD COLUMN replay_count      INTEGER     NOT NULL DEFAULT 0,
    ADD COLUMN last_replayed_at  TIMESTAMPTZ NULL;

CREATE TABLE reconciliation_runs (
    id                      UUID         PRIMARY KEY,
    trigger_type            VARCHAR(20)  NOT NULL,
    status                  VARCHAR(20)  NOT NULL,
    started_at              TIMESTAMPTZ  NOT NULL,
    finished_at             TIMESTAMPTZ  NULL,
    duration_ms             BIGINT       NULL,
    checked                 INTEGER      NOT NULL DEFAULT 0,
    resolved                INTEGER      NOT NULL DEFAULT 0,
    resolved_success        INTEGER      NOT NULL DEFAULT 0,
    resolved_failed         INTEGER      NOT NULL DEFAULT 0,
    still_pending           INTEGER      NOT NULL DEFAULT 0,
    unknown                 INTEGER      NOT NULL DEFAULT 0,
    conflicts               INTEGER      NOT NULL DEFAULT 0,
    ledger_inconsistencies  INTEGER      NOT NULL DEFAULT 0,
    escalated               INTEGER      NOT NULL DEFAULT 0,
    post_settlement_checked INTEGER      NOT NULL DEFAULT 0,
    max_recovery_ms         BIGINT       NULL,
    avg_recovery_ms         BIGINT       NULL,
    error                   VARCHAR(300) NULL,
    CONSTRAINT ck_recon_runs_trigger CHECK (trigger_type IN ('SCHEDULED', 'MANUAL', 'LAB')),
    CONSTRAINT ck_recon_runs_status CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED'))
);

CREATE INDEX ix_recon_runs_started ON reconciliation_runs (started_at DESC);

CREATE TABLE reconciliation_items (
    id             UUID         PRIMARY KEY,
    run_id         UUID         NOT NULL REFERENCES reconciliation_runs (id) ON DELETE RESTRICT,
    payment_id     UUID         NULL REFERENCES payments (id) ON DELETE RESTRICT,
    account_id     UUID         NULL REFERENCES accounts (id) ON DELETE RESTRICT,
    check_kind     VARCHAR(20)  NOT NULL,
    status_before  VARCHAR(20)  NULL,
    status_after   VARCHAR(20)  NULL,
    bank_status    VARCHAR(20)  NULL,
    outcome        VARCHAR(20)  NOT NULL,
    detail         VARCHAR(300) NOT NULL,
    recovery_ms    BIGINT       NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_recon_items_kind CHECK (check_kind IN ('IN_FLIGHT', 'POST_SETTLEMENT', 'LEDGER')),
    CONSTRAINT ck_recon_items_outcome CHECK (outcome IN
        ('RESOLVED', 'STILL_PENDING', 'UNKNOWN', 'ESCALATED', 'CONFLICT', 'CONSISTENT', 'LEDGER_MISMATCH'))
);

CREATE INDEX ix_recon_items_run ON reconciliation_items (run_id);
CREATE INDEX ix_recon_items_payment ON reconciliation_items (payment_id, created_at);

-- ---------------------------------------------------------------------------------------------
-- Reliability Lab
-- ---------------------------------------------------------------------------------------------
CREATE TABLE fault_scenarios (
    code                 VARCHAR(40)   PRIMARY KEY,
    title                VARCHAR(80)   NOT NULL,
    category             VARCHAR(30)   NOT NULL,
    bank_scenario        VARCHAR(30)   NOT NULL,
    default_delay_s      INTEGER       NOT NULL DEFAULT 0,
    default_duplicates   INTEGER       NOT NULL DEFAULT 1,
    default_requests     INTEGER       NOT NULL DEFAULT 1,
    status_api_down      BOOLEAN       NOT NULL DEFAULT FALSE,
    what_happens         VARCHAR(500)  NOT NULL,
    expected_outcome     VARCHAR(300)  NOT NULL,
    recovery             VARCHAR(500)  NOT NULL,
    sort_order           INTEGER       NOT NULL,
    CONSTRAINT ck_fault_bank_scenario CHECK (bank_scenario IN ('NORMAL_SUCCESS', 'INSUFFICIENT_FUNDS', 'BANK_TIMEOUT',
        'TIMEOUT_AFTER_COMMIT', 'BANK_UNAVAILABLE', 'NETWORK_FAILURE', 'DELAYED_SUCCESS', 'DELAYED_FAILURE',
        'DUPLICATE_CALLBACK', 'OUT_OF_ORDER_CALLBACK')),
    CONSTRAINT ck_fault_defaults CHECK (default_delay_s BETWEEN 0 AND 3600 AND default_duplicates BETWEEN 1 AND 10
        AND default_requests BETWEEN 1 AND 20)
);

INSERT INTO fault_scenarios (code, title, category, bank_scenario, default_delay_s, default_duplicates, default_requests,
                             status_api_down, what_happens, expected_outcome, recovery, sort_order) VALUES
('HAPPY_PATH', 'Normal transfer', 'BASELINE', 'NORMAL_SUCCESS', 0, 1, 1, FALSE,
 'The bank commits the transfer and confirms immediately.',
 'SUCCESS at once; one ledger debit and one credit.',
 'Nothing to recover. Use this as the baseline for comparison.', 10),
('LOST_ACKNOWLEDGEMENT', 'Lost acknowledgement', 'AMBIGUOUS', 'TIMEOUT_AFTER_COMMIT', 0, 1, 1, FALSE,
 'The bank debits and commits the transfer, but its reply never reaches PaySure. PaySure only sees a timeout.',
 'PENDING with funds held, then SUCCESS after reconciliation — ledger posted exactly once.',
 'Reconciliation asks the bank about the stable reference (it never re-sends), learns SUCCESS and finalises once.', 20),
('BANK_TIMEOUT', 'Timeout, nothing debited', 'AMBIGUOUS', 'BANK_TIMEOUT', 0, 1, 1, FALSE,
 'The bank does not answer in time and, internally, never executed the transfer.',
 'PENDING with funds held, then FAILED with the hold released — nothing debited.',
 'Reconciliation learns FAILED from the bank and releases the hold. Guessing "success" here would have lost money.', 30),
('NETWORK_DROP', 'Request lost in the network', 'AMBIGUOUS', 'NETWORK_FAILURE', 0, 1, 1, FALSE,
 'The connection drops before the request reaches the bank. PaySure cannot tell whether it arrived.',
 'PENDING, then FAILED (bank has no record) once the grace period has passed.',
 'Reconciliation sees "no record" and, only after the grace period, concludes nothing happened.', 40),
('BANK_DOWN', 'Bank unavailable', 'DEFINITIVE', 'BANK_UNAVAILABLE', 0, 1, 1, FALSE,
 'The bank refuses the request outright (maintenance/overload).',
 'FAILED immediately; hold released; nothing to reconcile.',
 'A definitive refusal is safe to fail at once — unlike a timeout.', 50),
('REMITTER_DECLINE', 'Declined by remitter bank', 'DEFINITIVE', 'INSUFFICIENT_FUNDS', 0, 1, 1, FALSE,
 'The remitter bank declines the debit.',
 'FAILED immediately; hold released.',
 'Nothing to recover: the decline is definitive.', 60),
('DELAYED_CONFIRMATION', 'Delayed confirmation', 'ASYNC', 'DELAYED_SUCCESS', 10, 1, 1, FALSE,
 'The bank accepts the request and confirms success only later, by callback.',
 'PENDING, then SUCCESS when the callback arrives.',
 'The callback finalises the payment; reconciliation would too if the callback were lost.', 70),
('DELAYED_DECLINE', 'Delayed decline', 'ASYNC', 'DELAYED_FAILURE', 10, 1, 1, FALSE,
 'The bank accepts the request, then reports failure later by callback.',
 'PENDING, then FAILED with the hold released.',
 'The callback finalises the payment and releases the held funds.', 80),
('DUPLICATE_CALLBACKS', 'Duplicate callbacks', 'DUPLICATES', 'DUPLICATE_CALLBACK', 5, 4, 1, FALSE,
 'The bank delivers the same SUCCESS callback several times (at-least-once delivery).',
 'SUCCESS once; extra callbacks ignored; exactly one ledger posting.',
 'Callback sequence numbers, the state machine and a one-journal-per-payment DB rule each block the duplicate.', 90),
('OUT_OF_ORDER_CALLBACKS', 'Out-of-order callbacks', 'DUPLICATES', 'OUT_OF_ORDER_CALLBACK', 5, 1, 1, FALSE,
 'The final SUCCESS callback arrives before an older "still processing" callback.',
 'SUCCESS; the stale callback is ignored and the status never regresses.',
 'Only strictly newer sequence numbers are applied, and terminal states have no exits.', 100),
('DUPLICATE_SUBMISSIONS', 'Client retry storm', 'DUPLICATES', 'NORMAL_SUCCESS', 0, 1, 10, FALSE,
 'The client sends the same payment many times at once with the same Idempotency-Key (double-taps, retries).',
 'Exactly one payment and one transfer; every other request gets the same payment back.',
 'The idempotency key is claimed atomically in the same transaction that creates the payment.', 110),
('STUCK_AT_BANK', 'Stuck at the bank', 'ESCALATION', 'DELAYED_SUCCESS', 600, 1, 1, FALSE,
 'The bank keeps answering "processing" for a long time.',
 'Stays PENDING (never guessed); escalated to manual review after repeated checks; SUCCESS once the bank finishes.',
 'Reconciliation leaves genuinely unknown outcomes pending and escalates them; fast-forward the bank to finish.', 120),
('STATUS_API_OUTAGE', 'Lost ack + status API outage', 'ESCALATION', 'TIMEOUT_AFTER_COMMIT', 0, 1, 1, TRUE,
 'The acknowledgement is lost AND the bank''s status-enquiry API is down, so PaySure cannot even ask.',
 'Stays PENDING (outcome unknown) until the status API is restored; then SUCCESS.',
 'Reconciliation records "unknown" and retries on the next run; it never fails or re-sends a payment it cannot verify.', 130);

CREATE TABLE lab_runs (
    id                           UUID         PRIMARY KEY,
    scenario_code                VARCHAR(40)  NOT NULL REFERENCES fault_scenarios (code) ON DELETE RESTRICT,
    started_by                   UUID         NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    payment_id                   UUID         NULL REFERENCES payments (id) ON DELETE RESTRICT,
    payer_account_id             UUID         NOT NULL REFERENCES accounts (id) ON DELETE RESTRICT,
    payee_account_id             UUID         NOT NULL REFERENCES accounts (id) ON DELETE RESTRICT,
    payer_vpa                    VARCHAR(64)  NOT NULL,
    payee_vpa                    VARCHAR(64)  NOT NULL,
    amount_paise                 BIGINT       NOT NULL,
    delay_seconds                INTEGER      NOT NULL,
    duplicate_callbacks          INTEGER      NOT NULL,
    duplicate_requests           INTEGER      NOT NULL,
    status_api_down              BOOLEAN      NOT NULL,
    payer_balance_before         BIGINT       NOT NULL,
    payer_reserved_before        BIGINT       NOT NULL,
    payee_balance_before         BIGINT       NOT NULL,
    payer_balance_after_submit   BIGINT       NULL,
    payer_reserved_after_submit  BIGINT       NULL,
    payee_balance_after_submit   BIGINT       NULL,
    status_after_submit          VARCHAR(20)  NULL,
    distinct_payments_created    INTEGER      NULL,
    score_before                 INTEGER      NULL,
    created_at                   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_lab_runs_amount CHECK (amount_paise > 0)
);

CREATE INDEX ix_lab_runs_created ON lab_runs (created_at DESC);

CREATE TABLE reliability_snapshots (
    id             UUID         PRIMARY KEY,
    captured_at    TIMESTAMPTZ  NOT NULL,
    trigger_type   VARCHAR(30)  NOT NULL,
    window_minutes INTEGER      NOT NULL,
    score          INTEGER      NOT NULL,
    components     TEXT         NOT NULL,
    CONSTRAINT ck_snapshot_score CHECK (score BETWEEN 0 AND 100)
);

CREATE INDEX ix_reliability_snapshots_captured ON reliability_snapshots (captured_at DESC);
