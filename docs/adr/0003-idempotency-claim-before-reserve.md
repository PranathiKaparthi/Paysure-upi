# ADR 0003 — Claim the idempotency key in the same transaction, before reserving funds

**Status:** accepted (Phase 2)

## Context
Clients retry. Ten identical requests may arrive at once. A "check if the key exists, then insert" sequence has a race in which two
requests both see "not found" and both move money.

## Decision
The first step of `POST /api/payments` is `INSERT … ON CONFLICT DO NOTHING` on `idempotency_keys`, in the **same transaction** as the
payment row. The loser of a race blocks on the unique index until the winner commits, then returns the winner's payment (HTTP 200 with
`Idempotent-Replayed: true`). The key stores a canonical SHA-256 hash of the request; the same key with a different body is a `409`.
Keys are scoped per caller and expire through a retention job. Only after the claim succeeds do we lock the payer account and reserve funds.

## Consequences
- Exactly one payment, one bank instruction and one ledger transfer per key, even under concurrency (acceptance test B and
  `PaymentConcurrencyTest#case8…`).
- The claim and the payment commit or roll back together, so a crash can never leave a key pointing at nothing.
- The client's responsibility: reuse the same key when retrying (the UI keeps it across a network error and offers "Retry safely").
