package com.paysure.bank.simulator;

import com.paysure.bank.BankContracts.TransferStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A scheduled callback from the simulated bank to PaySure (at-least-once delivery). */
@Entity
@Table(name = "sim_bank_callbacks")
public class SimCallback {

    @Id
    private UUID id;

    @Column(name = "bank_reference", nullable = false, length = 40)
    private String bankReference;

    @Column(nullable = false)
    private int sequence;

    @Enumerated(EnumType.STRING)
    @Column(name = "reported_status", nullable = false, length = 20)
    private TransferStatus reportedStatus;

    @Column(name = "deliver_at", nullable = false)
    private Instant deliverAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "delivery_attempts", nullable = false)
    private int deliveryAttempts;

    @Column(name = "last_error", length = 200)
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected SimCallback() {
    }

    SimCallback(String bankReference, int sequence, TransferStatus reportedStatus, Instant deliverAt, Instant now) {
        this.id = UUID.randomUUID();
        this.bankReference = bankReference;
        this.sequence = sequence;
        this.reportedStatus = reportedStatus;
        this.deliverAt = deliverAt;
        this.createdAt = now;
    }

    void markDelivered(Instant now) {
        deliveryAttempts++;
        deliveredAt = now;
        lastError = null;
    }

    void markFailed(Instant retryAt, String error) {
        deliveryAttempts++;
        deliverAt = retryAt;
        lastError = error == null ? null : error.substring(0, Math.min(200, error.length()));
    }

    public UUID getId() {
        return id;
    }

    public String getBankReference() {
        return bankReference;
    }

    public int getSequence() {
        return sequence;
    }

    public TransferStatus getReportedStatus() {
        return reportedStatus;
    }

    public Instant getDeliverAt() {
        return deliverAt;
    }

    public Instant getDeliveredAt() {
        return deliveredAt;
    }

    public int getDeliveryAttempts() {
        return deliveryAttempts;
    }
}
