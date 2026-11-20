package com.paysure.ledger.repository;

import com.paysure.ledger.domain.LedgerEntry;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    List<LedgerEntry> findByAccountIdOrderByCreatedAtDescIdDesc(UUID accountId, Pageable pageable);

    List<LedgerEntry> findByJournalId(UUID journalId);

    /** Net signed ledger movement for an account; must always equal {@code accounts.balance_paise}. */
    @Query("""
            select coalesce(sum(case when e.direction = com.paysure.ledger.domain.EntryDirection.CREDIT
                                     then e.amountPaise else -e.amountPaise end), 0)
            from LedgerEntry e where e.accountId = :accountId
            """)
    long netMovement(@Param("accountId") UUID accountId);
}
