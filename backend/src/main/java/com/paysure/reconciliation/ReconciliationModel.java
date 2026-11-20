package com.paysure.reconciliation;

import java.time.Instant;
import java.util.UUID;

/** Read models and enums of the reconciliation module. */
public final class ReconciliationModel {

    private ReconciliationModel() {
    }

    public enum TriggerType { SCHEDULED, MANUAL, LAB }

    public enum CheckKind {
        /** A PROCESSING/PENDING payment whose outcome must be learned from the bank. */
        IN_FLIGHT,
        /** A finished payment re-verified once against the bank's authoritative record. */
        POST_SETTLEMENT,
        /** A global ledger/hold invariant check. */
        LEDGER
    }

    public enum Outcome {
        /** Bank gave a definitive answer; payment finalised now (exactly once). */
        RESOLVED,
        /** Bank still processing (or no record yet within the grace period); left PENDING — never guessed. */
        STILL_PENDING,
        /** Bank could not be asked (status API down); left PENDING and retried next run. */
        UNKNOWN,
        /** Still unresolved after the configured attempts/age; flagged for manual review, still PENDING. */
        ESCALATED,
        /** Local final state contradicts the bank; flagged for manual review, nothing changed automatically. */
        CONFLICT,
        /** Checked and consistent. */
        CONSISTENT,
        /** A ledger or hold invariant is violated. */
        LEDGER_MISMATCH
    }

    public record RunSummary(UUID id, TriggerType trigger, String status, Instant startedAt, Instant finishedAt,
                             Long durationMs, int checked, int resolved, int resolvedSuccess, int resolvedFailed,
                             int stillPending, int unknown, int conflicts, int ledgerInconsistencies, int escalated,
                             int postSettlementChecked, Long maxRecoveryMs, Long avgRecoveryMs, String error) {
        public int manualReviewCases() {
            return conflicts + escalated;
        }
    }

    public record Item(UUID id, UUID runId, UUID paymentId, UUID accountId, CheckKind kind, String statusBefore,
                       String statusAfter, String bankStatus, Outcome outcome, String detail, Long recoveryMs,
                       Instant createdAt) {
    }

    public record ManualReviewCase(UUID paymentId, String status, String payerVpa, String payeeVpa, long amountPaise,
                                   String reason, int reconAttempts, Instant createdAt, Instant lastReconciledAt) {
    }
}
