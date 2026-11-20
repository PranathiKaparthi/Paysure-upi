-- PaySure Phase 1: immutable double-entry ledger.
-- Every balance change on `accounts` is accompanied by a journal whose entries sum to zero.
-- See docs/adr/0001-ledger-and-reserved-funds.md.

CREATE TABLE ledger_journals (
    id              UUID         PRIMARY KEY,
    journal_type    VARCHAR(30)  NOT NULL,
    reference_type  VARCHAR(30)  NOT NULL,
    reference_id    UUID         NOT NULL,
    description     VARCHAR(200) NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_ledger_journals_type CHECK (journal_type IN ('SANDBOX_FUNDING', 'SANDBOX_TOP_UP')),
    -- Exactly one journal of a given type per business reference. This is the database-level
    -- guarantee that, e.g., a payment can be posted to the ledger at most once.
    CONSTRAINT uq_ledger_journals_reference UNIQUE (journal_type, reference_id)
);

CREATE TABLE ledger_entries (
    id                   UUID        PRIMARY KEY,
    journal_id           UUID        NOT NULL REFERENCES ledger_journals (id) ON DELETE RESTRICT,
    account_id           UUID        NOT NULL REFERENCES accounts (id) ON DELETE RESTRICT,
    direction            VARCHAR(6)  NOT NULL,
    amount_paise         BIGINT      NOT NULL,
    balance_after_paise  BIGINT      NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_ledger_entries_direction CHECK (direction IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ck_ledger_entries_amount_positive CHECK (amount_paise > 0)
);

CREATE INDEX ix_ledger_entries_account_created ON ledger_entries (account_id, created_at DESC);
CREATE INDEX ix_ledger_entries_journal ON ledger_entries (journal_id);

-- Immutability: ledger rows can be inserted, never updated or deleted.
CREATE FUNCTION ledger_reject_mutation() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'ledger table % is append-only (% rejected)', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$;

CREATE TRIGGER trg_ledger_entries_immutable
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION ledger_reject_mutation();

CREATE TRIGGER trg_ledger_journals_immutable
    BEFORE UPDATE OR DELETE ON ledger_journals
    FOR EACH ROW EXECUTE FUNCTION ledger_reject_mutation();

-- Balance check: at COMMIT, every journal touched by the transaction must have >= 2 entries
-- whose credits equal its debits. Deferred so all legs can be inserted first.
CREATE FUNCTION ledger_assert_journal_balanced() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    net   BIGINT;
    legs  INTEGER;
BEGIN
    SELECT COALESCE(SUM(CASE WHEN direction = 'CREDIT' THEN amount_paise ELSE -amount_paise END), 0),
           COUNT(*)
      INTO net, legs
      FROM ledger_entries
     WHERE journal_id = NEW.journal_id;

    IF legs < 2 OR net <> 0 THEN
        RAISE EXCEPTION 'ledger journal % is unbalanced (legs=%, net=%)', NEW.journal_id, legs, net
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_ledger_entries_balanced
    AFTER INSERT ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger_assert_journal_balanced();
