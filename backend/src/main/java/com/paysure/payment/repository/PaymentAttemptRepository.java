package com.paysure.payment.repository;

import com.paysure.payment.domain.PaymentAttempt;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentAttemptRepository extends JpaRepository<PaymentAttempt, UUID> {

    Optional<PaymentAttempt> findByBankReference(String bankReference);

    List<PaymentAttempt> findByPaymentIdOrderByAttemptNoAsc(UUID paymentId);

    Optional<PaymentAttempt> findFirstByPaymentIdOrderByAttemptNoDesc(UUID paymentId);
}
