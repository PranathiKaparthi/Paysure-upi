package com.paysure.bank;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Value types exchanged across the bank adapter boundary. Kept free of persistence and Spring types
 * so a different bank implementation (or a real switch) could be substituted behind {@link BankGateway}.
 */
public final class BankContracts {

    private BankContracts() {
    }

    /** Optional sandbox fault injection for one transfer. {@code null} scenario = simulator default. */
    public record SimulationOptions(SimulationScenario scenario, int delaySeconds, int duplicateCallbacks) {
        public static final SimulationOptions DEFAULT = new SimulationOptions(null, 0, 1);

        public SimulationOptions {
            if (delaySeconds < 0 || delaySeconds > 600) {
                throw new IllegalArgumentException("delaySeconds must be 0-600");
            }
            if (duplicateCallbacks < 1 || duplicateCallbacks > 10) {
                throw new IllegalArgumentException("duplicateCallbacks must be 1-10");
            }
        }
    }

    /** The durable processing instruction sent to the bank. {@code bankReference} is stable and unique. */
    public record TransferInstruction(String bankReference, UUID payerAccountId, UUID payeeAccountId,
                                      long amountPaise, SimulationOptions simulation) {
        public TransferInstruction {
            Objects.requireNonNull(bankReference);
            Objects.requireNonNull(simulation);
        }
    }

    /** What the caller learned synchronously. Only SUCCESS, DECLINED and UNAVAILABLE are definitive. */
    public enum ResponseKind {
        /** Bank committed the transfer and said so. */
        SUCCESS,
        /** Bank refused the transfer (e.g. insufficient funds at the remitter bank). Nothing was debited. */
        DECLINED,
        /** Bank refused to accept the request at all (maintenance/overload). Nothing was recorded. */
        UNAVAILABLE,
        /** Bank accepted the request; the outcome will arrive by callback or status query. */
        ACCEPTED,
        /** No answer in time. The bank may or may not have committed: AMBIGUOUS. */
        TIMEOUT,
        /** Connection failed. The request may or may not have reached the bank: AMBIGUOUS. */
        NETWORK_ERROR;

        public boolean isAmbiguous() {
            return this == ACCEPTED || this == TIMEOUT || this == NETWORK_ERROR;
        }
    }

    public record SubmitResult(ResponseKind kind, String reason) {
    }

    /** Authoritative status according to the bank. NOT_FOUND = the bank has no record of the reference. */
    public enum TransferStatus { PROCESSING, SUCCESS, FAILED, NOT_FOUND }

    public record StatusResult(String bankReference, TransferStatus status, String reason) {
    }

    /** Asynchronous notification from the bank. {@code sequence} increases per reference. */
    public record Callback(UUID callbackId, String bankReference, int sequence, TransferStatus status, String reason) {
    }

    /** The bank's own view of a transfer: what it committed vs. what it told the caller. Sandbox insight only. */
    public record BankSideView(String bankReference, SimulationScenario scenario, TransferStatus committedStatus,
                               ResponseKind responseDelivered, String failureReason, long amountPaise,
                               Instant resolvesAt, Instant committedAt, Instant createdAt,
                               List<BankSideCallback> callbacks) {
    }

    public record BankSideCallback(UUID id, int sequence, TransferStatus reportedStatus, Instant deliverAt,
                                   Instant deliveredAt, int deliveryAttempts) {
    }

    /** Thrown when even a status query cannot be answered (the result is unknown, not negative). */
    public static class BankUnreachableException extends RuntimeException {
        public BankUnreachableException(String message) {
            super(message);
        }
    }
}
