package com.paysure.account.domain;

import com.paysure.common.persistence.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;

/** A VPA linked to exactly one sandbox account. Uniqueness is enforced by a database constraint. */
@Entity
@Table(name = "upi_handles")
public class UpiHandle extends AuditedEntity {

    @Id
    private UUID id;

    @Column(nullable = false, length = 64)
    private String vpa;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountStatus status;

    protected UpiHandle() {
    }

    public UpiHandle(Vpa vpa, UUID accountId) {
        this.id = UUID.randomUUID();
        this.vpa = vpa.value();
        this.accountId = Objects.requireNonNull(accountId);
        this.status = AccountStatus.ACTIVE;
    }

    public void deactivate() {
        status = AccountStatus.INACTIVE;
    }

    public boolean isActive() {
        return status == AccountStatus.ACTIVE;
    }

    public UUID getId() {
        return id;
    }

    public String getVpa() {
        return vpa;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public AccountStatus getStatus() {
        return status;
    }
}
