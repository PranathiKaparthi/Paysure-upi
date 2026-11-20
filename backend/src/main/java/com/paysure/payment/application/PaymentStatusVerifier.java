package com.paysure.payment.application;

import com.paysure.bank.BankContracts.BankUnreachableException;
import com.paysure.bank.BankContracts.StatusResult;
import com.paysure.bank.BankGateway;
import com.paysure.payment.application.PaymentFinalizer.Effect;
import com.paysure.payment.domain.Payment;
import com.paysure.payment.domain.PaymentAttempt;
import com.paysure.payment.domain.TransitionSource;
import com.paysure.payment.repository.PaymentAttemptRepository;
import com.paysure.payment.repository.PaymentRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * "What actually happened?" — asks the bank for the status of a payment's stable reference and applies
 * the authoritative answer. It NEVER re-submits a transfer. Used by the manual "check status" action and
 * by the scheduled reconciliation worker.
 */
@Service
public class PaymentStatusVerifier {

    private static final Logger log = LoggerFactory.getLogger(PaymentStatusVerifier.class);

    private final PaymentRepository payments;
    private final PaymentAttemptRepository attempts;
    private final BankGateway bank;
    private final PaymentFinalizer finalizer;

    public PaymentStatusVerifier(PaymentRepository payments, PaymentAttemptRepository attempts, BankGateway bank,
                                 PaymentFinalizer finalizer) {
        this.payments = payments;
        this.attempts = attempts;
        this.bank = bank;
        this.finalizer = finalizer;
    }

    /**
     * @param bankStatus  the bank's answer, or null if it was not asked / could not answer
     * @param unreachable true if the bank could not be asked (outcome UNKNOWN, not "negative")
     */
    public record Verification(Effect effect, StatusResult bankStatus, boolean unreachable) {
    }

    /** Verifies an unfinished payment and applies the bank's answer (exactly once). */
    public Verification verify(UUID paymentId, TransitionSource source) {
        Payment payment = payments.findById(paymentId).orElseThrow();
        if (payment.getStatus().isTerminal()) {
            return new Verification(Effect.NO_CHANGE, null, false);
        }
        PaymentAttempt attempt = attempts.findFirstByPaymentIdOrderByAttemptNoDesc(paymentId).orElse(null);
        if (attempt == null) {
            return new Verification(Effect.NO_CHANGE, null, false);
        }
        StatusResult status;
        try {
            status = bank.queryStatus(attempt.getBankReference());
        } catch (BankUnreachableException e) {
            log.warn("Status query for {} failed: {} — outcome still unknown", attempt.getBankReference(), e.getMessage());
            return new Verification(Effect.NO_CHANGE, null, true);
        }
        return new Verification(finalizer.applyStatus(paymentId, status, source), status, false);
    }

    /** Read-only: the bank's current record for a payment, without changing anything locally. */
    public Verification peek(UUID paymentId) {
        PaymentAttempt attempt = attempts.findFirstByPaymentIdOrderByAttemptNoDesc(paymentId).orElse(null);
        if (attempt == null) {
            return new Verification(Effect.NO_CHANGE, null, false);
        }
        try {
            return new Verification(Effect.NO_CHANGE, bank.queryStatus(attempt.getBankReference()), false);
        } catch (BankUnreachableException e) {
            return new Verification(Effect.NO_CHANGE, null, true);
        }
    }
}
