package com.paysure.payment.repository;

import com.paysure.payment.domain.PaymentStateChange;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentStateHistoryRepository extends JpaRepository<PaymentStateChange, Long> {

    List<PaymentStateChange> findByPaymentIdOrderByIdAsc(UUID paymentId);
}
