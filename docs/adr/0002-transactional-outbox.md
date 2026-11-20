# ADR 0002 — Transactional outbox instead of publishing to Kafka inside the payment request

**Status:** accepted (Phase 4)

## Context
Payment state changes must produce events (`payment.completed`, `reconciliation.run`, `risk.alert`, …) for webhooks and other consumers.
Writing to PostgreSQL and then to Kafka are two systems with no shared transaction, so a naive "save, then send" can lose an event
(crash in between) or announce one for a payment that rolled back.

## Decision
Events are inserted into `outbox_events` **in the same PostgreSQL transaction** as the business change (`Propagation.MANDATORY`, so it is
impossible to write one outside a transaction). A separate publisher, serialised by an advisory lock, sends rows to Kafka in order with
`acks=all` and an idempotent producer, and marks them published. Consumers record `(consumer, eventId)` in `inbox_events` in the same
transaction as their effect.

## Consequences
- A Kafka outage delays events; it never fails or double-charges a payment.
- Delivery is **at-least-once**; correctness of the *business effect* comes from the inbox, not from Kafka. We do not claim Kafka
  exactly-once semantics for money movement — money only moves in PostgreSQL.
- A failing head-of-line row pauses the queue until it is parked as DEAD (20 attempts), trading throughput for ordering. Throughput is not a goal.
