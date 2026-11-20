-- PaySure Phase 5: rule-based risk alerts (educational simulation; not a real fraud determination).

CREATE TABLE risk_alerts (
    id                UUID          PRIMARY KEY,
    user_id           UUID          NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    payment_id        UUID          NULL REFERENCES payments (id) ON DELETE RESTRICT,
    payer_vpa         VARCHAR(64)   NOT NULL,
    payee_vpa         VARCHAR(64)   NOT NULL,
    amount_paise      BIGINT        NOT NULL,
    score             INTEGER       NOT NULL,
    level             VARCHAR(10)   NOT NULL,
    action            VARCHAR(10)   NOT NULL,
    blocked           BOOLEAN       NOT NULL DEFAULT FALSE,
    rules             TEXT          NOT NULL,
    explanation       TEXT          NOT NULL,
    suggested_action  VARCHAR(300)  NOT NULL,
    status            VARCHAR(14)   NOT NULL DEFAULT 'OPEN',
    reviewed_by       UUID          NULL REFERENCES users (id) ON DELETE RESTRICT,
    reviewed_at       TIMESTAMPTZ   NULL,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_risk_alerts_amount CHECK (amount_paise > 0),
    CONSTRAINT ck_risk_alerts_score CHECK (score BETWEEN 0 AND 100),
    CONSTRAINT ck_risk_alerts_level CHECK (level IN ('LOW', 'MEDIUM', 'HIGH')),
    CONSTRAINT ck_risk_alerts_action CHECK (action IN ('ALLOW', 'REVIEW', 'BLOCK')),
    CONSTRAINT ck_risk_alerts_status CHECK (status IN ('OPEN', 'ACKNOWLEDGED', 'DISMISSED')),
    CONSTRAINT ck_risk_alerts_review CHECK ((status = 'OPEN') = (reviewed_at IS NULL)),
    -- A blocked attempt never became a payment.
    CONSTRAINT ck_risk_alerts_blocked CHECK (NOT blocked OR payment_id IS NULL)
);

CREATE INDEX ix_risk_alerts_user ON risk_alerts (user_id, created_at DESC);
CREATE INDEX ix_risk_alerts_status ON risk_alerts (status, created_at DESC);
CREATE INDEX ix_risk_alerts_payment ON risk_alerts (payment_id) WHERE payment_id IS NOT NULL;

-- "Is this payee new to the payer?" is asked on every payment.
CREATE INDEX ix_payments_payer_payee_success ON payments (payer_user_id, payee_account_id) WHERE status = 'SUCCESS';
