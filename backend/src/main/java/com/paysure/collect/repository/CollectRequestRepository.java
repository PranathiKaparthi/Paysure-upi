package com.paysure.collect.repository;

import com.paysure.collect.domain.CollectRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CollectRequestRepository extends JpaRepository<CollectRequest, UUID> {

    List<CollectRequest> findByPayerUserIdOrderByCreatedAtDesc(UUID payerUserId, Pageable page);

    List<CollectRequest> findByRequesterUserIdOrderByCreatedAtDesc(UUID requesterUserId, Pageable page);
}
