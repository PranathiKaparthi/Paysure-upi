package com.paysure.bank.simulator;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SimCallbackRepository extends JpaRepository<SimCallback, UUID> {

    @Query("select c from SimCallback c where c.deliveredAt is null and c.deliverAt <= :now order by c.deliverAt, c.id")
    List<SimCallback> findDue(@Param("now") Instant now, Pageable page);

    List<SimCallback> findByBankReferenceOrderByDeliverAtAsc(String bankReference);
}
