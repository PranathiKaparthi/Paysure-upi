package com.paysure.bank.simulator;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SimTransferRepository extends JpaRepository<SimTransfer, String> {

    @Query("""
            select t from SimTransfer t
            where t.committedStatus = com.paysure.bank.BankContracts.TransferStatus.PROCESSING
              and t.resolvesAt <= :now""")
    List<SimTransfer> findDue(@Param("now") Instant now);
}
