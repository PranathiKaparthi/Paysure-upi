package com.paysure.payment.application;

import com.paysure.account.application.AccountLocker;
import com.paysure.account.domain.Account;
import com.paysure.common.error.BusinessRuleException;
import com.paysure.risk.RiskEngine;
import com.paysure.risk.RiskService;
import com.paysure.bank.BankContracts.SimulationOptions;
import com.paysure.bank.BankContracts.TransferInstruction;
import com.paysure.payment.domain.Payment;
import com.paysure.payment.domain.PaymentAttempt;
import com.paysure.payment.domain.PaymentStatus;
import com.paysure.payment.domain.TransitionSource;
import com.paysure.payment.repository.IdempotencyRecordRepository;
import com.paysure.payment.repository.PaymentAttemptRepository;
import com.paysure.payment.repository.PaymentRepository;
import com.paysure.payment.repository.PaymentStateHistoryRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase A of a payment, in ONE database transaction:
 * claim the idempotency key → persist the intent → reserve funds → write the durable bank instruction.
 *
 * <p>Because the key claim and the payment commit together, a concurrent request with the same key either
 * waits and then sees the finished payment, or (if this transaction rolls back) claims the key itself.
 * No database locks are held while the bank is called (that happens after this commits).
 */
@Service
public class PaymentIntake {

    private final IdempotencyRecordRepository idempotency;
    private final PaymentRepository payments;
    private final PaymentAttemptRepository attempts;
    private final PaymentStateHistoryRepository history;
    private final AccountLocker accountLocker;
    private final PaymentProperties props;
    private final PaymentMetrics metrics;
    private final PaymentEventRecorder events;
    private final RiskService risk;
    private final Clock clock;

    public PaymentIntake(IdempotencyRecordRepository idempotency, PaymentRepository payments,
                         PaymentAttemptRepository attempts, PaymentStateHistoryRepository history,
                         AccountLocker accountLocker, PaymentProperties props, PaymentMetrics metrics,
                         PaymentEventRecorder events, RiskService risk, Clock clock) {
        this.idempotency = idempotency;
        this.payments = payments;
        this.attempts = attempts;
        this.history = history;
        this.accountLocker = accountLocker;
        this.props = props;
        this.metrics = metrics;
        this.events = events;
        this.risk = risk;
        this.clock = clock;
    }

    /** Validated parties of a payment (resolved before the key is claimed). */
    public record Parties(UUID payerAccountId, String payerVpa, UUID payeeUserId, UUID payeeAccountId, String payeeVpa) {
    }

    /** Either the key already existed ({@code existingPaymentId}), or a new payment was created. */
    public record Outcome(UUID paymentId, boolean newlyCreated, String existingRequestHash,
                          UUID attemptId, TransferInstruction instruction) {
        public boolean needsBankSubmission() {
            return instruction != null;
        }
    }

    @Transactional
    public Outcome claimAndReserve(PaymentCommand cmd, Parties parties, String requestHash) {
        Instant now = clock.instant();
        UUID paymentId = UUID.randomUUID();
        int claimed = idempotency.tryClaim(UUID.randomUUID(), cmd.callerUserId(), cmd.idempotencyKey(), requestHash,
                paymentId, now, now.plus(props.idempotencyTtl()));
        if (claimed == 0) {
            var existing = idempotency.findByCallerUserIdAndIdempotencyKey(cmd.callerUserId(), cmd.idempotencyKey())
                    .orElseThrow(() -> new IllegalStateException("idempotency key vanished after conflict"));
            return new Outcome(existing.getPaymentId(), false, existing.getRequestHash(), null, null);
        }

        // Risk check. It runs only here, after we own the idempotency key, so N concurrent duplicates are evaluated once.
        RiskEngine.Assessment assessment = risk.enabled() ? risk.assess(cmd.callerUserId(), parties.payeeAccountId(), cmd.amountPaise()) : null;
        if (assessment != null && risk.shouldBlock(assessment)) {
            risk.recordBlocked(assessment, cmd.callerUserId(), parties.payerVpa(), parties.payeeVpa(), cmd.amountPaise());
            // Throwing rolls back the key claim too: nothing was created and the client may try again later.
            throw new BusinessRuleException("RISK_BLOCKED", "Payment refused by the sandbox risk policy. "
                    + String.join(" ", assessment.explanation()) + " (Educational simulation.)");
        }

        Account payer = accountLocker.lock(parties.payerAccountId());
        Payment payment = Payment.create(paymentId, cmd.callerUserId(), parties.payerAccountId(), parties.payerVpa(),
                parties.payeeUserId(), parties.payeeAccountId(), parties.payeeVpa(), cmd.amountPaise(), cmd.note(),
                cmd.collectRequestId());
        payments.saveAndFlush(payment);
        history.save(payment.initialHistory(now));
        metrics.requested();
        events.created(payment); // outbox row commits with the payment
        if (assessment != null && assessment.alertWorthy()) {
            risk.recordAlert(assessment, cmd.callerUserId(), paymentId, parties.payerVpa(), parties.payeeVpa(), cmd.amountPaise());
        }

        if (!payer.isActive() || payer.availablePaise() < cmd.amountPaise()) {
            String code = payer.isActive() ? "INSUFFICIENT_FUNDS" : "ACCOUNT_INACTIVE";
            String reason = payer.isActive() ? "Insufficient available balance" : "Payer account is not active";
            payment.recordFailure(code, reason);
            history.save(payment.transition(PaymentStatus.FAILED, TransitionSource.API, reason, now));
            metrics.failed();
            events.failed(payment, TransitionSource.API);
            return new Outcome(paymentId, true, requestHash, null, null);
        }

        payer.reserve(cmd.amountPaise());
        payment.markFundsHeld();
        SimulationOptions sim = cmd.simulation() == null ? SimulationOptions.DEFAULT : cmd.simulation();
        PaymentAttempt attempt = attempts.save(new PaymentAttempt(paymentId, 1, newBankReference(),
                sim.scenario(), sim.delaySeconds(), sim.duplicateCallbacks()));
        history.save(payment.transition(PaymentStatus.PROCESSING, TransitionSource.API,
                "Funds reserved; durable instruction " + attempt.getBankReference() + " written", now));

        TransferInstruction instruction = new TransferInstruction(attempt.getBankReference(), parties.payerAccountId(),
                parties.payeeAccountId(), cmd.amountPaise(), sim);
        return new Outcome(paymentId, true, requestHash, attempt.getId(), instruction);
    }

    private static String newBankReference() {
        return "PSB" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase(Locale.ROOT);
    }
}
