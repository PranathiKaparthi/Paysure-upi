-- PaySure Phase 1: identity, sandbox accounts and UPI-style virtual payment addresses.
-- All monetary values are integral paise (BIGINT). No floating point anywhere.

CREATE TABLE users (
    id             UUID         PRIMARY KEY,
    email          VARCHAR(254) NOT NULL,
    password_hash  VARCHAR(100) NOT NULL,
    full_name      VARCHAR(100) NOT NULL,
    role           VARCHAR(20)  NOT NULL,
    enabled        BOOLEAN      NOT NULL DEFAULT TRUE,
    version        BIGINT       NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_users_email UNIQUE (email),
    CONSTRAINT ck_users_email_lower CHECK (email = lower(email)),
    CONSTRAINT ck_users_role CHECK (role IN ('USER', 'ADMIN_DEMO'))
);

-- CUSTOMER accounts belong to a user and can never go below zero available funds.
-- SYSTEM accounts (e.g. the sandbox treasury that mints demo money) have no owner
-- and are allowed to carry a negative balance so every posting stays double-entry.
CREATE TABLE accounts (
    id              UUID         PRIMARY KEY,
    user_id         UUID         NULL REFERENCES users (id) ON DELETE RESTRICT,
    account_type    VARCHAR(20)  NOT NULL,
    account_number  VARCHAR(20)  NOT NULL,
    ifsc            VARCHAR(11)  NOT NULL,
    display_name    VARCHAR(60)  NOT NULL,
    currency        CHAR(3)      NOT NULL DEFAULT 'INR',
    balance_paise   BIGINT       NOT NULL DEFAULT 0,
    reserved_paise  BIGINT       NOT NULL DEFAULT 0,
    status          VARCHAR(20)  NOT NULL,
    version         BIGINT       NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_accounts_number UNIQUE (account_number),
    CONSTRAINT ck_accounts_type CHECK (account_type IN ('CUSTOMER', 'SYSTEM')),
    CONSTRAINT ck_accounts_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT ck_accounts_currency CHECK (currency = 'INR'),
    CONSTRAINT ck_accounts_owner CHECK (
        (account_type = 'CUSTOMER' AND user_id IS NOT NULL) OR
        (account_type = 'SYSTEM' AND user_id IS NULL)),
    CONSTRAINT ck_accounts_reserved_nonneg CHECK (reserved_paise >= 0),
    CONSTRAINT ck_accounts_customer_funds CHECK (
        account_type = 'SYSTEM' OR (balance_paise >= 0 AND reserved_paise <= balance_paise))
);

CREATE INDEX ix_accounts_user ON accounts (user_id);

CREATE TABLE upi_handles (
    id          UUID         PRIMARY KEY,
    vpa         VARCHAR(64)  NOT NULL,
    account_id  UUID         NOT NULL REFERENCES accounts (id) ON DELETE RESTRICT,
    status      VARCHAR(20)  NOT NULL,
    version     BIGINT       NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_upi_handles_vpa UNIQUE (vpa),
    CONSTRAINT ck_upi_handles_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT ck_upi_handles_format CHECK (vpa ~ '^[a-z0-9][a-z0-9._-]{2,29}@[a-z]{3,20}$')
);

CREATE INDEX ix_upi_handles_account ON upi_handles (account_id);

-- The sandbox treasury: source of all simulated demo money.
INSERT INTO accounts (id, user_id, account_type, account_number, ifsc, display_name, balance_paise, status)
VALUES ('00000000-0000-0000-0000-000000000001', NULL, 'SYSTEM', 'SYS000000001', 'PSBK0000001',
        'Sandbox Treasury', 0, 'ACTIVE');
