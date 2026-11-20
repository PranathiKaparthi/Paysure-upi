package com.paysure.payment.domain;

import com.paysure.common.persistence.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Payment aggregate. Status changes go only through {@link #transition}, which consults the central
 * {@link PaymentStateMachine} and returns the history row to append. Concurrent writers are serialised
 * by a pessimistic row lock in the application layer and, as a backstop, by {@code @Version}.
 */
@Entity
@Table(name = "payments")
public class Payment extends AuditedEntity {

    @Id
    private UUID id;

    @Column(name = "payer_user_id", nullable = false)
    private UUID payerUserId;

    @Column(name = "payer_account_id", nullable = false)
    private UUID payerAccountId;

    @Column(name = "payer_vpa", nullable = false, length = 64)
    private String payerVpa;

    @Column(name = "payee_user_id", nullable = false)
    private UUID payeeUserId;

    @Column(name = "payee_account_id", nullable = false)
    private UUID payeeAccountId;

    @Column(name = "payee_vpa", nullable = false, length = 64)
    private String payeeVpa;

    @Column(name = "amount_paise", nullable = false)
    private long amountPaise;

    @Column(length = 100)
    private String note;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "hold_status", nullable = false, length = 20)
    private HoldStatus holdStatus;

    @Column(name = "failure_code", length = 40)
    private String failureCode;

    @Column(name = "failure_reason", length = 200)
    private String failureReason;

    @Column(name = "collect_request_id")
    private UUID collectRequestId;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected Payment() {
    }

    public static Payment create(UUID id, UUID payerUserId, UUID payerAccountId, String payerVpa,
                                 UUID payeeUserId, UUID payeeAccountId, String payeeVpa,
                                 long amountPaise, String note, UUID collectRequestId) {
        if (amountPaise <= 0) {
            throw new IllegalArgumentException("amount must be positive paise");
        }
        if (payerAccountId.equals(payeeAccountId)) {
            throw new IllegalArgumentException("payer and payee accounts must differ");
        }
        Payment p = new Payment();
        p.id = Objects.requireNonNull(id);
        p.payerUserId = payerUserId;
        p.payerAccountId = payerAccountId;
        p.payerVpa = payerVpa;
        p.payeeUserId = payeeUserId;
        p.payeeAccountId = payeeAccountId;
        p.payeeVpa = payeeVpa;
        p.amountPaise = amountPaise;
        p.note = note == null || note.isBlank() ? null : note.trim();
        p.status = PaymentStatus.CREATED;
        p.holdStatus = HoldStatus.NONE;
        p.collectRequestId = collectRequestId;
        return p;
    }

    public PaymentStateChange initialHistory(Instant now) {
        return new PaymentStateChange(id, null, PaymentStatus.CREATED, TransitionSource.API, "Payment intent recorded", now);
    }

    /**
     * Guarded transition. Hold bookkeeping must already be consistent with the target state
     * (the database enforces the same rules with CHECK constraints).
     */
    public PaymentStateChange transition(PaymentStatus to, TransitionSource source, String reason, Instant now) {
        PaymentStateMachine.requireTransition(status, to);
        if (to == PaymentStatus.SUCCESS && holdStatus != HoldStatus.CAPTURED) {
            throw new IllegalStateException("SUCCESS requires the hold to be captured");
        }
        if (to == PaymentStatus.FAILED && holdStatus == HoldStatus.HELD) {
            throw new IllegalStateException("FAILED requires the hold to be released first");
        }
        if ((to == PaymentStatus.PROCESSING || to == PaymentStatus.PENDING) && holdStatus != HoldStatus.HELD) {
            throw new IllegalStateException(to + " requires funds to be held");
        }
        PaymentStatus from = status;
        status = to;
        if (to.isTerminal()) {
            completedAt = now;
        }
        return new PaymentStateChange(id, from, to, source, reason, now);
    }

    public void markFundsHeld() {
        requireHold(HoldStatus.NONE);
        holdStatus = HoldStatus.HELD;
    }

    public void markHoldCaptured() {
        requireHold(HoldStatus.HELD);
        holdStatus = HoldStatus.CAPTURED;
    }

    public void markHoldReleased() {
        requireHold(HoldStatus.HELD);
        holdStatus = HoldStatus.RELEASED;
    }

    public void recordFailure(String code, String reason) {
        this.failureCode = code;
        this.failureReason = reason == null ? null : reason.substring(0, Math.min(200, reason.length()));
    }

    private void requireHold(HoldStatus expected) {
        if (holdStatus != expected) {
            throw new IllegalStateException("hold is " + holdStatus + ", expected " + expected);
        }
    }

    public boolean involves(UUID userId) {
        return payerUserId.equals(userId) || payeeUserId.equals(userId);
    }

    public UUID getId() {
        return id;
    }

    public UUID getPayerUserId() {
        return payerUserId;
    }

    public UUID getPayerAccountId() {
        return payerAccountId;
    }

    public String getPayerVpa() {
        return payerVpa;
    }

    public UUID getPayeeUserId() {
        return payeeUserId;
    }

    public UUID getPayeeAccountId() {
        return payeeAccountId;
    }

    public String getPayeeVpa() {
        return payeeVpa;
    }

    public long getAmountPaise() {
        return amountPaise;
    }

    public String getNote() {
        return note;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public HoldStatus getHoldStatus() {
        return holdStatus;
    }

    public String getFailureCode() {
        return failureCode;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public UUID getCollectRequestId() {
        return collectRequestId;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
