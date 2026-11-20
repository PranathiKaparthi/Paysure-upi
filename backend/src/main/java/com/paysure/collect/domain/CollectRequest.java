package com.paysure.collect.domain;

import com.paysure.common.error.BusinessRuleException;
import com.paysure.common.persistence.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * "Request money": the requester (payee) asks the payer to pay. Only the payer can approve or decline;
 * approval creates the payment through the normal payment engine. A unique index guarantees that at most
 * one payment can ever reference a collect request.
 */
@Entity
@Table(name = "collect_requests")
public class CollectRequest extends AuditedEntity {

    @Id
    private UUID id;

    @Column(name = "requester_user_id", nullable = false)
    private UUID requesterUserId;

    @Column(name = "requester_account_id", nullable = false)
    private UUID requesterAccountId;

    @Column(name = "requester_vpa", nullable = false, length = 64)
    private String requesterVpa;

    @Column(name = "payer_user_id", nullable = false)
    private UUID payerUserId;

    @Column(name = "payer_account_id", nullable = false)
    private UUID payerAccountId;

    @Column(name = "payer_vpa", nullable = false, length = 64)
    private String payerVpa;

    @Column(name = "amount_paise", nullable = false)
    private long amountPaise;

    @Column(length = 100)
    private String note;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CollectStatus status;

    @Column(name = "payment_id")
    private UUID paymentId;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    protected CollectRequest() {
    }

    public CollectRequest(UUID requesterUserId, UUID requesterAccountId, String requesterVpa, UUID payerUserId,
                          UUID payerAccountId, String payerVpa, long amountPaise, String note, Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.requesterUserId = requesterUserId;
        this.requesterAccountId = requesterAccountId;
        this.requesterVpa = requesterVpa;
        this.payerUserId = payerUserId;
        this.payerAccountId = payerAccountId;
        this.payerVpa = payerVpa;
        this.amountPaise = amountPaise;
        this.note = note == null || note.isBlank() ? null : note.trim();
        this.status = CollectStatus.PENDING;
        this.expiresAt = expiresAt;
    }

    /** Lazily expires a pending request whose deadline passed. Returns true if it changed. */
    public boolean expireIfDue(Instant now) {
        if (status == CollectStatus.PENDING && !expiresAt.isAfter(now)) {
            status = CollectStatus.EXPIRED;
            decidedAt = now;
            return true;
        }
        return false;
    }

    public void approve(Instant now) {
        requirePending(now);
        status = CollectStatus.APPROVED;
        decidedAt = now;
    }

    public void decline(Instant now) {
        requirePending(now);
        status = CollectStatus.DECLINED;
        decidedAt = now;
    }

    public void cancel(Instant now) {
        requirePending(now);
        status = CollectStatus.CANCELLED;
        decidedAt = now;
    }

    public void linkPayment(UUID paymentId) {
        if (this.paymentId == null) {
            this.paymentId = paymentId;
        }
    }

    private void requirePending(Instant now) {
        expireIfDue(now);
        if (status != CollectStatus.PENDING) {
            throw new BusinessRuleException("COLLECT_NOT_PENDING", "This request is already " + status + ".");
        }
    }

    /** Status as of {@code now}, without mutating (for read paths). */
    public CollectStatus effectiveStatus(Instant now) {
        return status == CollectStatus.PENDING && !expiresAt.isAfter(now) ? CollectStatus.EXPIRED : status;
    }

    public UUID getId() {
        return id;
    }

    public UUID getRequesterUserId() {
        return requesterUserId;
    }

    public UUID getRequesterAccountId() {
        return requesterAccountId;
    }

    public String getRequesterVpa() {
        return requesterVpa;
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

    public long getAmountPaise() {
        return amountPaise;
    }

    public String getNote() {
        return note;
    }

    public CollectStatus getStatus() {
        return status;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }
}
