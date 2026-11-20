# ADR 0001 — Double-entry ledger with a materialised balance and reserved funds

- **Status:** Accepted — implemented (Phase 1: ledger + balances; Phase 2: reservations used by payments)
- **Date:** 2026-11-20

## Context

PaySure must show that simulated money is never created, lost or spent twice — even under
concurrent requests, retries and ambiguous bank outcomes. We need:

1. Fast, correct "can this account afford X?" checks under concurrency.
2. An auditable history that cannot be silently edited.
3. A way to earmark money for an in-flight payment whose outcome is not yet known
   (the bank timed out), without debiting it twice or letting it be spent elsewhere.

## Decision

### 1. Integral paise everywhere
All amounts are `BIGINT` paise in PostgreSQL and `long` in Java. Arithmetic uses
`Math.addExact/subtractExact` so overflow throws instead of wrapping. The frontend parses
rupee strings with a regex into `BigInt` paise — no floating point at any layer.

### 2. Immutable double-entry journals
- `ledger_journals` groups the legs of one business event; `ledger_entries` holds the legs.
- Every journal must have ≥ 2 legs whose CREDITs equal its DEBITs. This is checked in Java
  (`LedgerService.JournalCommand.validateBalanced`) **and** by a `DEFERRABLE INITIALLY DEFERRED`
  constraint trigger that re-checks every journal touched by a transaction at `COMMIT`.
- `UPDATE`/`DELETE` on either table raises an exception (trigger). Corrections are new journals.
- `UNIQUE (journal_type, reference_id)` makes "post this business event" idempotent at the
  database level. In Phase 2 a payment can therefore produce at most one `PAYMENT_TRANSFER`
  journal, no matter how many duplicate callbacks, reconciliation runs or redelivered Kafka
  events try to finalise it.
- Demo money is minted from a `SYSTEM` **Sandbox Treasury** account, which is allowed to go
  negative. This keeps seed funding double-entry: the sum of all balances is always zero.

### 3. Materialised balance on `accounts`, protected by row locks
`accounts.balance_paise` is updated in the *same transaction* that appends the ledger entries,
only by `LedgerService`. Each entry stores `balance_after_paise` for a verifiable running balance.

Concurrency control:
- **Pessimistic row locks** (`SELECT … FOR UPDATE`) via `AccountLocker`. Money movement is a
  short, contended critical section where "lock, check, write" is simpler to reason about than
  optimistic retries.
- **Deterministic lock order**: accounts are locked in ascending canonical UUID string order,
  so A→B and B→A transfers cannot deadlock.
- **Refresh under lock**: if an `Account` was already loaded in the persistence context (e.g. by
  an ownership check), Hibernate would return the cached, possibly stale instance. `AccountLocker`
  calls `refresh(entity, PESSIMISTIC_WRITE)` so new balances are always computed from the locked row.
- `@Version` on accounts remains as a second line of defence for any code path that forgets to lock.

Database backstops (independent of application code):
`CHECK (account_type = 'SYSTEM' OR (balance_paise >= 0 AND reserved_paise <= balance_paise))`.

### 4. Reserved funds (used from Phase 2)
`available = balance_paise − reserved_paise`. A payment will:
1. lock the payer, check `available ≥ amount`, and `reserved += amount` (no ledger entry yet);
2. on confirmed **SUCCESS**: post the transfer journal whose payer leg is `CAPTURE_HOLD` (release the
   reservation and debit in the same locked step) and whose payee leg is a `SETTLEMENT` credit;
3. on confirmed **FAILURE**: `reserved -= amount` (no journal);
4. on an **ambiguous** outcome: keep the hold and status `PENDING` until reconciliation learns
   the bank's real outcome. The hold prevents the same money from being spent twice meanwhile.

## Alternatives considered

| Option | Why not (for this project) |
|---|---|
| Balance derived only by summing the ledger | Correct but every affordability check scans history; needs the same locking anyway. |
| Optimistic locking with retry only | Works, but hot accounts (treasury, popular merchants) cause retry storms; harder to demo deterministically. |
| `SERIALIZABLE` isolation everywhere | Correct, but serialization failures surface as generic retries and obscure *which* invariant was protected. |
| Single-entry "transactions" table | Cannot prove money is conserved; no natural place for holds or reversals. |
| Redis for balances | Not durable enough to be a system of record; PostgreSQL stays authoritative. |

## Consequences

- The treasury row is locked for every account opening/top-up. Fine for a sandbox; a real
  system would shard funding accounts.
- Tests assert invariants directly in SQL (`IntegrationTest.assertLedgerInvariants`): every
  journal nets to zero, every balance equals its net ledger movement, no customer account is
  below zero available.
- Ledger tables cannot be truncated by tests via normal deletes; tests isolate by unique data.
