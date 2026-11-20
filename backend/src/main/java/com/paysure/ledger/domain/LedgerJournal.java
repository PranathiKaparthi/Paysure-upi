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

/** Groups the balanced entries of one business event. Append-only (enforced by a DB trigger). */
@Entity
@Immutable
@Table(name = "ledger_journals")
public class LedgerJournal {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "journal_type", nullable = false, length = 30)
    private JournalType type;

    @Column(name = "reference_type", nullable = false, length = 30)
    private String referenceType;

    @Column(name = "reference_id", nullable = false)
    private UUID referenceId;

    @Column(length = 200)
    private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected LedgerJournal() {
    }

    public LedgerJournal(JournalType type, String referenceType, UUID referenceId, String description, Instant now) {
        this.id = UUID.randomUUID();
        this.type = type;
        this.referenceType = referenceType;
        this.referenceId = referenceId;
        this.description = description;
        this.createdAt = now;
    }

    public UUID getId() {
        return id;
    }

    public JournalType getType() {
        return type;
    }

    public String getReferenceType() {
        return referenceType;
    }

    public UUID getReferenceId() {
        return referenceId;
    }

    public String getDescription() {
        return description;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
