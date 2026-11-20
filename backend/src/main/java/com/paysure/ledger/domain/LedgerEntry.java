package com.paysure.ledger.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** One immutable leg of a journal. {@code balanceAfterPaise} gives a verifiable running balance. */
@Entity
@Immutable
@Table(name = "ledger_entries")
public class LedgerEntry {

    @Id
    private UUID id;

    @Column(name = "journal_id", nullable = false)
    private UUID journalId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 6)
    private EntryDirection direction;

    @Column(name = "amount_paise", nullable = false)
    private long amountPaise;

    @Column(name = "balance_after_paise", nullable = false)
    private long balanceAfterPaise;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected LedgerEntry() {
    }

    public LedgerEntry(UUID journalId, UUID accountId, EntryDirection direction, long amountPaise,
                       long balanceAfterPaise, Instant now) {
        this.id = UUID.randomUUID();
        this.journalId = journalId;
        this.accountId = accountId;
        this.direction = direction;
        this.amountPaise = amountPaise;
        this.balanceAfterPaise = balanceAfterPaise;
        this.createdAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getJournalId() {
        return journalId;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public EntryDirection getDirection() {
        return direction;
    }

    public long getAmountPaise() {
        return amountPaise;
    }

    public long getBalanceAfterPaise() {
        return balanceAfterPaise;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
