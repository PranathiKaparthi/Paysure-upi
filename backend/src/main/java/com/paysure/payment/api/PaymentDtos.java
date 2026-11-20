package com.paysure.payment.api;

import com.paysure.bank.BankContracts.BankSideView;
import com.paysure.bank.BankContracts.ResponseKind;
import com.paysure.bank.BankContracts.StatusResult;
import com.paysure.bank.SimulationScenario;
import com.paysure.payment.application.PaymentFinalizer.Effect;
import com.paysure.payment.domain.HoldStatus;
import com.paysure.payment.domain.Payment;
import com.paysure.payment.domain.PaymentAttempt;
import com.paysure.payment.domain.PaymentStateChange;
import com.paysure.payment.domain.PaymentStatus;
import com.paysure.payment.domain.TransitionSource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class PaymentDtos {

    private PaymentDtos() {
    }

    public record SimulationRequest(
            @NotNull SimulationScenario scenario,
            @Min(0) @Max(600) Integer delaySeconds,
            @Min(1) @Max(10) Integer duplicateCallbacks) {
    }

    public record PaymentRequest(
            @NotBlank @Size(max = 64) String payerVpa,
            @NotBlank @Size(max = 64) String payeeVpa,
            @NotNull @Positive @Max(10_000_000L) Long amountPaise,
            @Size(max = 100) String note,
            /** Sandbox fault injection; ADMIN_DEMO only. */
            @Valid SimulationRequest simulation) {
    }

    public enum Direction { SENT, RECEIVED }

    public record PaymentResponse(
            UUID id,
            PaymentStatus status,
            String statusMessage,
            Direction direction,
            String payerVpa,
            String payeeVpa,
            long amountPaise,
            String note,
            HoldStatus holdStatus,
            String failureCode,
            String failureReason,
            String bankReference,
            UUID collectRequestId,
            Instant createdAt,
            Instant updatedAt,
            Instant completedAt) {

        public static PaymentResponse of(Payment p, String bankReference, UUID viewerId) {
            Direction direction = p.getPayerUserId().equals(viewerId) ? Direction.SENT
                    : p.getPayeeUserId().equals(viewerId) ? Direction.RECEIVED : null;
            return new PaymentResponse(p.getId(), p.getStatus(), explain(p), direction, p.getPayerVpa(), p.getPayeeVpa(),
                    p.getAmountPaise(), p.getNote(), p.getHoldStatus(), p.getFailureCode(), p.getFailureReason(),
                    bankReference, p.getCollectRequestId(), p.getCreatedAt(), p.getUpdatedAt(), p.getCompletedAt());
        }

        /** Plain-language status, as a user-facing app would show it. */
        static String explain(Payment p) {
            return switch (p.getStatus()) {
                case CREATED -> "Payment recorded.";
                case PROCESSING -> "Submitted to the bank. Your funds are on hold.";
                case PENDING -> "The bank has not confirmed the outcome yet. Your funds are on hold and the payment "
                        + "will be verified automatically. Do not pay again — retrying could pay twice.";
                case SUCCESS -> "Payment successful.";
                case FAILED -> p.getHoldStatus() == HoldStatus.RELEASED
                        ? "Payment failed. No money left your account; the hold has been released."
                        : "Payment failed. No money left your account.";
            };
        }
    }

    public record HistoryResponse(PaymentStatus fromStatus, PaymentStatus toStatus, TransitionSource source,
                                  String reason, Instant at) {
        static HistoryResponse of(PaymentStateChange h) {
            return new HistoryResponse(h.getFromStatus(), h.getToStatus(), h.getSource(), h.getReason(), h.getCreatedAt());
        }
    }

    public record AttemptResponse(int attemptNo, String bankReference, SimulationScenario requestedScenario,
                                  ResponseKind responseKind, Instant submittedAt, Instant responseAt,
                                  int callbacksReceived, int callbacksIgnored, int lastCallbackSeq) {
        static AttemptResponse of(PaymentAttempt a) {
            return new AttemptResponse(a.getAttemptNo(), a.getBankReference(), a.getScenario(), a.getResponseKind(),
                    a.getSubmittedAt(), a.getResponseAt(), a.getCallbacksReceived(), a.getCallbacksIgnored(),
                    a.getLastCallbackSeq());
        }
    }

    public record PaymentDetailResponse(PaymentResponse payment, List<HistoryResponse> history,
                                        List<AttemptResponse> attempts, BankSideView simulatedBank) {
        public static PaymentDetailResponse of(Payment p, List<PaymentStateChange> history, List<PaymentAttempt> attempts,
                                               BankSideView bank, UUID viewerId) {
            String ref = attempts.isEmpty() ? null : attempts.get(attempts.size() - 1).getBankReference();
            return new PaymentDetailResponse(PaymentResponse.of(p, ref, viewerId),
                    history.stream().map(HistoryResponse::of).toList(),
                    attempts.stream().map(AttemptResponse::of).toList(), bank);
        }
    }

    /** Lightweight status for polling. */
    public record StatusResponse(UUID id, PaymentStatus status, HoldStatus holdStatus, String failureCode, long amountPaise,
                                 Instant updatedAt) {
    }

    public record StatusCheckResponse(Effect effect, StatusResult bankStatus, PaymentResponse payment) {
    }
}
