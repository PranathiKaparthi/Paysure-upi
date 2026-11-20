package com.paysure.payment.application;

import com.paysure.account.domain.Account;
import com.paysure.account.domain.UpiHandle;
import com.paysure.account.domain.Vpa;
import com.paysure.account.repository.AccountRepository;
import com.paysure.account.repository.UpiHandleRepository;
import com.paysure.bank.BankContracts.ResponseKind;
import com.paysure.bank.BankContracts.SubmitResult;
import com.paysure.bank.BankGateway;
import com.paysure.common.error.BadRequestException;
import com.paysure.common.error.BusinessRuleException;
import com.paysure.common.error.ConflictException;
import com.paysure.common.error.ForbiddenException;
import com.paysure.common.error.NotFoundException;
import com.paysure.payment.application.PaymentIntake.Outcome;
import com.paysure.payment.application.PaymentIntake.Parties;
import com.paysure.payment.domain.Payment;
import com.paysure.payment.repository.IdempotencyRecordRepository;
import com.paysure.payment.repository.PaymentRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Payment orchestration. Deliberately NOT transactional itself:
 * <ol>
 *   <li>replay check + validation (read-only);</li>
 *   <li>{@link PaymentIntake} — one transaction: claim key, persist intent, reserve funds, write instruction;</li>
 *   <li>call the bank with no database transaction or lock open;</li>
 *   <li>{@link PaymentFinalizer} — one transaction: record and apply the outcome.</li>
 * </ol>
 * An ambiguous bank outcome leaves the payment PENDING with funds held. It is never re-submitted;
 * the outcome is learned through callbacks or status verification by the stable bank reference.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private static final Pattern KEY = Pattern.compile("^[A-Za-z0-9._:-]{8,100}$");

    private final PaymentIntake intake;
    private final PaymentFinalizer finalizer;
    private final BankGateway bank;
    private final IdempotencyRecordRepository idempotency;
    private final PaymentRepository payments;
    private final UpiHandleRepository handles;
    private final AccountRepository accounts;
    private final PaymentProperties props;
    private final PaymentMetrics metrics;
    private final Clock clock;

    public PaymentService(PaymentIntake intake, PaymentFinalizer finalizer, BankGateway bank,
                          IdempotencyRecordRepository idempotency, PaymentRepository payments,
                          UpiHandleRepository handles, AccountRepository accounts, PaymentProperties props,
                          PaymentMetrics metrics, Clock clock) {
        this.intake = intake;
        this.finalizer = finalizer;
        this.bank = bank;
        this.idempotency = idempotency;
        this.payments = payments;
        this.handles = handles;
        this.accounts = accounts;
        this.props = props;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** Result of {@link #initiate}: the logical payment and whether this call was an idempotent replay. */
    public record Initiated(Payment payment, boolean replayed) {
    }

    public Initiated initiate(PaymentCommand cmd) {
        if (cmd.idempotencyKey() == null || !KEY.matcher(cmd.idempotencyKey()).matches()) {
            throw new BadRequestException("INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key header is required: 8-100 characters of letters, digits, '.', '_', ':' or '-'.");
        }
        String hash = cmd.canonicalHash();

        // A replay must succeed even if, e.g., the payee VPA has since been deactivated.
        var known = idempotency.findByCallerUserIdAndIdempotencyKey(cmd.callerUserId(), cmd.idempotencyKey());
        if (known.isPresent()) {
            return replay(cmd, known.get().getRequestHash(), hash, known.get().getPaymentId());
        }

        Parties parties = validate(cmd);
        Outcome outcome = intake.claimAndReserve(cmd, parties, hash);
        if (!outcome.newlyCreated()) {
            return replay(cmd, outcome.existingRequestHash(), hash, outcome.paymentId()); // lost the race for the key
        }
        if (outcome.needsBankSubmission()) {
            submitToBank(outcome);
        }
        return new Initiated(load(outcome.paymentId()), false);
    }

    private void submitToBank(Outcome outcome) {
        Instant submittedAt = clock.instant();
        SubmitResult result;
        try {
            result = bank.submit(outcome.instruction());
        } catch (RuntimeException e) {
            // Any unexpected error is AMBIGUOUS: the bank may have committed. Never assume failure.
            log.warn("Bank submit for {} threw {}; treating outcome as unknown", outcome.instruction().bankReference(),
                    e.toString());
            result = new SubmitResult(ResponseKind.NETWORK_ERROR, "Unexpected error talking to bank: " + e.getClass().getSimpleName());
        }
        try {
            finalizer.applySubmitResult(outcome.paymentId(), outcome.attemptId(), result, submittedAt);
        } catch (RuntimeException e) {
            // The durable instruction exists, so the payment stays PROCESSING and status verification
            // (manual check now, reconciliation in Phase 3) will learn the real outcome.
            log.error("Could not record bank outcome {} for payment {}; left for status verification",
                    result.kind(), outcome.paymentId(), e);
        }
    }

    private Initiated replay(PaymentCommand cmd, String storedHash, String requestHash, UUID paymentId) {
        if (!storedHash.equals(requestHash)) {
            throw new ConflictException("IDEMPOTENCY_KEY_REUSED",
                    "This Idempotency-Key was already used for a different payment request.");
        }
        metrics.duplicatePrevented();
        idempotency.recordReplay(cmd.callerUserId(), cmd.idempotencyKey(), clock.instant());
        return new Initiated(load(paymentId), true);
    }

    private Payment load(UUID paymentId) {
        return payments.findById(paymentId).orElseThrow();
    }

    /** Steps 1–6 of the payment flow. Every check here happens before anything is written. */
    Parties validate(PaymentCommand cmd) {
        if (cmd.amountPaise() <= 0 || cmd.amountPaise() > props.maxAmountPaise()) {
            throw new BadRequestException("INVALID_AMOUNT",
                    "Amount must be between 1 and " + props.maxAmountPaise() + " paise.");
        }
        if (cmd.simulation() != null && !cmd.callerIsAdminDemo()) {
            throw new ForbiddenException("SIMULATION_NOT_ALLOWED",
                    "Only ADMIN_DEMO users may choose bank simulation scenarios.");
        }
        Vpa payerVpa = Vpa.parse(cmd.payerVpa());
        Vpa payeeVpa = Vpa.parse(cmd.payeeVpa());

        UpiHandle payerHandle = handles.findByVpa(payerVpa.value()).filter(UpiHandle::isActive).orElse(null);
        Account payerAccount = payerHandle == null ? null : accounts.findById(payerHandle.getAccountId()).orElse(null);
        if (payerAccount == null || !payerAccount.isOwnedBy(cmd.callerUserId())) {
            throw new ForbiddenException("PAYER_NOT_OWNED", "You can only pay from an active VPA that belongs to you.");
        }
        if (!payerAccount.isActive()) {
            throw new BusinessRuleException("ACCOUNT_INACTIVE", "Payer account is not active.");
        }

        Account payeeAccount = handles.findByVpa(payeeVpa.value()).filter(UpiHandle::isActive)
                .flatMap(h -> accounts.findById(h.getAccountId()))
                .filter(Account::isActive)
                .orElseThrow(() -> new NotFoundException("PAYEE_NOT_FOUND", "Payee VPA not found or not active."));
        if (payeeAccount.getId().equals(payerAccount.getId())) {
            throw new BusinessRuleException("SELF_TRANSFER_NOT_ALLOWED",
                    "Payer and payee resolve to the same account.");
        }
        return new Parties(payerAccount.getId(), payerVpa.value(), payeeAccount.getUserId(), payeeAccount.getId(),
                payeeVpa.value());
    }
}
