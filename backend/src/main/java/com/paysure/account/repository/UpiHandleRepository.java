package com.paysure.account.repository;

import com.paysure.account.domain.UpiHandle;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UpiHandleRepository extends JpaRepository<UpiHandle, UUID> {

    Optional<UpiHandle> findByVpa(String vpa);

    boolean existsByVpa(String vpa);

    long countByAccountId(UUID accountId);

    List<UpiHandle> findByAccountIdInOrderByCreatedAtAsc(Collection<UUID> accountIds);
}
