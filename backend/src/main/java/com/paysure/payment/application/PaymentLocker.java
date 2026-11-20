package com.paysure.payment.application;

import com.paysure.common.error.NotFoundException;
import com.paysure.payment.domain.Payment;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code SELECT ... FOR UPDATE} on a payment, refreshed so the entity reflects the locked row (see
 * AccountLocker for why). Every status change happens while holding this lock, so a bank response, any
 * number of callbacks and a status check racing for the same payment are applied one at a time.
 *
 * <p>Lock order everywhere: payment row first, then account rows (ascending id). Intake locks only an
 * account and inserts a new payment, so no cycle is possible.
 */
@Component
public class PaymentLocker {

    private final EntityManager em;

    public PaymentLocker(EntityManager em) {
        this.em = em;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Payment lock(UUID paymentId) {
        Payment payment = em.find(Payment.class, paymentId);
        if (payment == null) {
            throw new NotFoundException("PAYMENT_NOT_FOUND", "Payment not found.");
        }
        em.refresh(payment, LockModeType.PESSIMISTIC_WRITE);
        return payment;
    }

    /** Re-reads an entity whose writes are serialised by the payment lock already held. */
    @Transactional(propagation = Propagation.MANDATORY)
    public <T> T refresh(T entity) {
        em.refresh(entity);
        return entity;
    }
}
