package com.paysure.ledger.domain;

/**
 * Business reason for a journal. Combined with the reference id it is unique, which makes
 * "post this business event to the ledger" idempotent at the database level.
 */
public enum JournalType {
    /** Initial demo money credited when a sandbox account is opened (reference = account id). */
    SANDBOX_FUNDING,
    /** Development-only top-up performed by an ADMIN_DEMO user (reference = top-up request id). */
    SANDBOX_TOP_UP,
    /** A confirmed payment (reference = payment id) — at most one per payment, enforced by the DB. */
    PAYMENT_TRANSFER
}
