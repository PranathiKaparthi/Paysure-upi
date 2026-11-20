package com.paysure.payment.repository;

import com.paysure.payment.domain.Payment;
import com.paysure.payment.domain.PaymentStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    @Query("""
            select p from Payment p
            where (p.payerUserId = :userId or p.payeeUserId = :userId)
              and (:status is null or p.status = :status)
            order by p.createdAt desc, p.id desc""")
    List<Payment> findForUser(@Param("userId") UUID userId, @Param("status") PaymentStatus status, Pageable page);

    /** Payments whose outcome is not yet known and that have been in that state since before {@code olderThan}. */
    List<Payment> findByStatusInAndUpdatedAtBeforeOrderByUpdatedAtAsc(Collection<PaymentStatus> statuses,
                                                                     Instant olderThan, Pageable page);

    long countByStatus(PaymentStatus status);
}
