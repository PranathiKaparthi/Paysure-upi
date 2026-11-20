package com.paysure.payment.domain;

import com.paysure.bank.BankContracts.ResponseKind;
import com.paysure.bank.SimulationScenario;
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
 * The durable processing instruction for one bank submission, written (and committed) BEFORE the bank
 * is called. Its {@code bankReference} lets PaySure ask the bank "what happened?" after a crash or an
 * ambiguous response, without ever initiating a second transfer.
 */
@Entity
@Table(name = "payment_attempts")
public class PaymentAttempt extends AuditedEntity {

    @Id
    private UUID id;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "attempt_no", nullable = false)
    private int attemptNo;

    @Column(name = "bank_reference", nullable = false, length = 40)
    private String bankReference;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private SimulationScenario scenario;

    @Column(name = "delay_seconds", nullable = false)
    private int delaySeconds;

    @Column(name = "duplicate_callbacks", nullable = false)
    private int duplicateCallbacks;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "response_kind", length = 20)
    private ResponseKind responseKind;

    @Column(name = "response_at")
    private Instant responseAt;

    @Column(name = "last_callback_seq", nullable = false)
    private int lastCallbackSeq;

    @Column(name = "callbacks_received", nullable = false)
    private int callbacksReceived;

    @Column(name = "callbacks_ignored", nullable = false)
    private int callbacksIgnored;

    protected PaymentAttempt() {
    }

    public PaymentAttempt(UUID paymentId, int attemptNo, String bankReference, SimulationScenario scenario,
                          int delaySeconds, int duplicateCallbacks) {
        this.id = UUID.randomUUID();
        this.paymentId = paymentId;
        this.attemptNo = attemptNo;
        this.bankReference = bankReference;
        this.scenario = scenario;
        this.delaySeconds = delaySeconds;
        this.duplicateCallbacks = duplicateCallbacks;
    }

    public void recordResponse(ResponseKind kind, Instant submitted, Instant now) {
        if (responseKind == null) {
            this.submittedAt = submitted;
            this.responseKind = kind;
            this.responseAt = now;
        }
    }

    /**
     * Registers a callback. Returns false for duplicates and stale (out-of-order) callbacks, which must be
     * ignored: only a strictly newer sequence number may change anything.
     */
    public boolean acceptCallback(int sequence) {
        if (sequence <= lastCallbackSeq) {
            callbacksIgnored++;
            return false;
        }
        lastCallbackSeq = sequence;
        callbacksReceived++;
        return true;
    }

    public UUID getId() {
        return id;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public int getAttemptNo() {
        return attemptNo;
    }

    public String getBankReference() {
        return bankReference;
    }

    public SimulationScenario getScenario() {
        return scenario;
    }

    public int getDelaySeconds() {
        return delaySeconds;
    }

    public int getDuplicateCallbacks() {
        return duplicateCallbacks;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public ResponseKind getResponseKind() {
        return responseKind;
    }

    public Instant getResponseAt() {
        return responseAt;
    }

    public int getLastCallbackSeq() {
        return lastCallbackSeq;
    }

    public int getCallbacksReceived() {
        return callbacksReceived;
    }

    public int getCallbacksIgnored() {
        return callbacksIgnored;
    }
}
