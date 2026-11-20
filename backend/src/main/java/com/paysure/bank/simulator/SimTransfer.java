package com.paysure.bank.simulator;

import com.paysure.bank.BankContracts.ResponseKind;
import com.paysure.bank.BankContracts.TransferStatus;
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
 * The simulated bank's own record of a transfer: what it ACTUALLY committed ({@code committedStatus})
 * kept separately from what it told the caller ({@code responseKind}).
 */
@Entity
@Table(name = "sim_bank_transfers")
public class SimTransfer extends AuditedEntity {

    @Id
    @Column(name = "bank_reference", length = 40)
    private String bankReference;

    @Column(name = "payer_account_id", nullable = false)
    private UUID payerAccountId;

    @Column(name = "payee_account_id", nullable = false)
    private UUID payeeAccountId;

    @Column(name = "amount_paise", nullable = false)
    private long amountPaise;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private SimulationScenario scenario;

    @Enumerated(EnumType.STRING)
    @Column(name = "committed_status", nullable = false, length = 20)
    private TransferStatus committedStatus;

    @Column(name = "failure_reason", length = 100)
    private String failureReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "response_kind", nullable = false, length = 20)
    private ResponseKind responseKind;

    /** For delayed scenarios: when the PROCESSING transfer reaches its final outcome. */
    @Column(name = "resolves_at")
    private Instant resolvesAt;

    @Column(name = "committed_at")
    private Instant committedAt;

    /** Final outcome a delayed transfer will reach (not persisted separately: derived from scenario). */
    protected SimTransfer() {
    }

    SimTransfer(String bankReference, UUID payerAccountId, UUID payeeAccountId, long amountPaise,
                SimulationScenario scenario, TransferStatus committedStatus, String failureReason,
                ResponseKind responseKind, Instant resolvesAt, Instant now) {
        this.bankReference = bankReference;
        this.payerAccountId = payerAccountId;
        this.payeeAccountId = payeeAccountId;
        this.amountPaise = amountPaise;
        this.scenario = scenario;
        this.committedStatus = committedStatus;
        this.failureReason = failureReason;
        this.responseKind = responseKind;
        this.resolvesAt = resolvesAt;
        this.committedAt = committedStatus == TransferStatus.PROCESSING ? null : now;
    }

    /** Moves a delayed transfer to its scenario-defined final state once it is due. */
    boolean resolveIfDue(Instant now) {
        if (committedStatus != TransferStatus.PROCESSING || resolvesAt == null || resolvesAt.isAfter(now)) {
            return false;
        }
        if (scenario == SimulationScenario.DELAYED_FAILURE) {
            committedStatus = TransferStatus.FAILED;
            failureReason = "Declined by remitter bank after processing";
        } else {
            committedStatus = TransferStatus.SUCCESS;
        }
        committedAt = now;
        return true;
    }

    public String getBankReference() {
        return bankReference;
    }

    public UUID getPayerAccountId() {
        return payerAccountId;
    }

    public UUID getPayeeAccountId() {
        return payeeAccountId;
    }

    public long getAmountPaise() {
        return amountPaise;
    }

    public SimulationScenario getScenario() {
        return scenario;
    }

    public TransferStatus getCommittedStatus() {
        return committedStatus;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public ResponseKind getResponseKind() {
        return responseKind;
    }

    public Instant getResolvesAt() {
        return resolvesAt;
    }

    public Instant getCommittedAt() {
        return committedAt;
    }
}
