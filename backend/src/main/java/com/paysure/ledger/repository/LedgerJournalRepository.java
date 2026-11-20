package com.paysure.ledger.repository;

import com.paysure.ledger.domain.JournalType;
import com.paysure.ledger.domain.LedgerJournal;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerJournalRepository extends JpaRepository<LedgerJournal, UUID> {

    boolean existsByTypeAndReferenceId(JournalType type, UUID referenceId);
}
