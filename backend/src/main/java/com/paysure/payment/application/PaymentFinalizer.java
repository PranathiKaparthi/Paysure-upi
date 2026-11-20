package com.paysure.payment.application;

import com.paysure.account.application.AccountLocker;
import com.paysure.bank.BankContracts.Callback;
import com.paysure.bank.BankContracts.StatusResult;
import com.paysure.bank.BankContracts.SubmitResult;
import com.paysure.ledger.application.LedgerService;
import com.paysure.ledger.application.LedgerService.JournalCommand;
import com.paysure.ledger.application.LedgerService.Leg;
import com.paysure.ledger.domain.JournalType;
import com.paysure.payment.domain.HoldStatus;
import com.paysure.payment.domain.Payment;
import com.paysure.payment.domain.PaymentAttempt;
import com.paysure.payment.domain.PaymentStatus;
import com.paysure.payment.domain.TransitionSource;
import com.paysure.payment.repository.PaymentAttemptRepository;
import com.paysure.payment.repository.PaymentStateHistoryRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies bank outcomes to payments — from the synchronous response, from callbacks, or from status
 * queries — exactly once. Every entry point locks the payment row first, so concurrent signals about
 * the same payment are serialised. Idempotency is enforced in three layers:
 * <ol>
 *   <li>the state machine: terminal states accept no transitions, so nothing can regress or flip;</li>
 *   <li>callback sequence numbers: duplicates and stale callbacks are ignored;</li>
 *   <li>the ledger's {@code UNIQUE(journal_type, reference_id)}: at most one PAYMENT_TRANSFER per payment.</li>
 * </ol>
 */
@Service
public class PaymentFinalizer {

    private static final Logger log = LoggerFactory.getLogger(PaymentFinalizer.class);

    /** What applying a signal did. CONFLICT = the bank now contradicts a terminal local state. */
    public enum Effect { SETTLED_SUCCESS, SETTLED_FAILURE, MARKED_PENDING, NO_CHANGE, IGNORED_STALE, CONFLICT }

    private final PaymentLocker paymentLocker;
    private final AccountLocker accountLocker;
    private final PaymentAttemptRepository attempts;
    private final PaymentStateHistoryRepository history;
    private final LedgerService ledger;
    private final PaymentMetrics metrics;
    private final PaymentEventRecorder events;
    private final PaymentProperties props;
    private final Clock clock;

    public PaymentFinalizer(PaymentLocker paymentLocker, AccountLocker accountLocker, PaymentAttemptRepository attempts,
                            PaymentStateHistoryRepository history, LedgerService ledger, PaymentMetrics metrics,
                            PaymentEventRecorder events, PaymentProperties props, Clock clock) {
        this.paymentLocker = paymentLocker;
        this.accountLocker = accountLocker;
        this.attempts = attempts;
        this.history = history;
        this.ledger = ledger;
        this.metrics = metrics;
        this.events = events;
        this.props = props;
        this.clock = clock;
    }

    @Transactional
    public Effect applySubmitResult(UUID paymentId, UUID attemptId, SubmitResult result, Instant submittedAt) {
        Payment payment = paymentLocker.lock(paymentId);
        PaymentAttempt attempt = attempts.findById(attemptId).orElseThrow();
        Instant now = clock.instant();
        attempt.recordResponse(result.kind(), submittedAt, now);
        metrics.processed(Duration.between(payment.getCreatedAt(), now));
        return switch (result.kind()) {
            case SUCCESS -> settleSuccess(payment, TransitionSource.BANK_RESPONSE, "Bank confirmed the transfer");
            case DECLINED -> settleFailure(payment, "BANK_DECLINED", result.reason(), TransitionSource.BANK_RESPONSE);
            case UNAVAILABLE -> settleFailure(payment, "BANK_UNAVAILABLE", result.reason(), TransitionSource.BANK_RESPONSE);
            case ACCEPTED -> markPending(payment, TransitionSource.BANK_RESPONSE,
                    "Bank accepted the request; awaiting final status");
            case TIMEOUT -> markPending(payment, TransitionSource.BANK_RESPONSE,
                    "No response from bank in time; outcome unknown. Funds held; will verify, never re-send");
            case NETWORK_ERROR -> markPending(payment, TransitionSource.BANK_RESPONSE,
                    "Connection to bank failed; outcome unknown. Funds held; will verify, never re-send");
        };
    }

    @Transactional
    public Effect applyCallback(Callback callback) {
        PaymentAttempt found = attempts.findByBankReference(callback.bankReference()).orElse(null);
        if (found == null) {
            log.warn("Callback {} for unknown bank reference {} ignored", callback.callbackId(), callback.bankReference());
            return Effect.IGNORED_STALE;
        }
        Payment payment = paymentLocker.lock(found.getPaymentId());
        // Re-read under the payment lock: concurrent callbacks for this payment are now serialised.
        PaymentAttempt attempt = paymentLocker.refresh(found);
        if (!attempt.acceptCallback(callback.sequence())) {
            metrics.callbackIgnored();
            log.info("Ignored duplicate/stale callback seq={} for {} (last seq={}, payment {})",
                    callback.sequence(), callback.bankReference(), attempt.getLastCallbackSeq(), payment.getStatus());
            return Effect.IGNORED_STALE;
        }
        return switch (callback.status()) {
            case SUCCESS -> settleSuccess(payment, TransitionSource.BANK_CALLBACK, "Bank callback: transfer successful");
            case FAILED -> settleFailure(payment, "BANK_DECLINED",
                    callback.reason() == null ? "Bank callback: transfer failed" : callback.reason(), TransitionSource.BANK_CALLBACK);
            case PROCESSING -> markPending(payment, TransitionSource.BANK_CALLBACK, "Bank callback: still processing");
            case NOT_FOUND -> Effect.NO_CHANGE;
        };
    }

    /**
     * Applies an authoritative status-query answer. NOT_FOUND is only treated as a definitive failure
     * after a grace period, because a just-sent request may not be visible at the bank yet.
     */
    @Transactional
    public Effect applyStatus(UUID paymentId, StatusResult status, TransitionSource source) {
        Payment payment = paymentLocker.lock(paymentId);
        return switch (status.status()) {
            case SUCCESS -> settleSuccess(payment, source, "Status verified with bank: SUCCESS");
            case FAILED -> settleFailure(payment, "BANK_DECLINED",
                    "Status verified with bank: FAILED" + (status.reason() == null ? "" : " (" + status.reason() + ")"), source);
            case PROCESSING -> markPending(payment, source, "Status verified with bank: still processing");
            case NOT_FOUND -> {
                Instant now = clock.instant();
                if (!payment.getStatus().isTerminal()
                        && payment.getCreatedAt().plus(props.notFoundGrace()).isBefore(now)) {
                    yield settleFailure(payment, "BANK_NO_RECORD",
                            "Bank has no record of this reference after the grace period; nothing was debited", source);
                }
                yield Effect.NO_CHANGE;
            }
        };
    }

    private Effect settleSuccess(Payment payment, TransitionSource source, String reason) {
        if (payment.getStatus() == PaymentStatus.SUCCESS) {
            return Effect.NO_CHANGE;
        }
        if (payment.getStatus() == PaymentStatus.FAILED) {
            log.error("DISCREPANCY: bank reports SUCCESS for payment {} already FAILED locally ({}). Needs manual review.",
                    payment.getId(), source);
            return Effect.CONFLICT;
        }
        // One balanced journal per payment; the hold is converted into a real debit in the same step.
        ledger.post(new JournalCommand(JournalType.PAYMENT_TRANSFER, "PAYMENT", payment.getId(),
                "UPI transfer " + payment.getPayerVpa() + " -> " + payment.getPayeeVpa(),
                List.of(Leg.captureHold(payment.getPayerAccountId(), payment.getAmountPaise()),
                        Leg.settlementCredit(payment.getPayeeAccountId(), payment.getAmountPaise()))));
        payment.markHoldCaptured();
        history.save(payment.transition(PaymentStatus.SUCCESS, source, reason, clock.instant()));
        metrics.succeeded();
        events.completed(payment, source);
        log.info("Payment {} SUCCESS via {}", payment.getId(), source);
        return Effect.SETTLED_SUCCESS;
    }

    private Effect settleFailure(Payment payment, String code, String reason, TransitionSource source) {
        if (payment.getStatus() == PaymentStatus.FAILED) {
            return Effect.NO_CHANGE;
        }
        if (payment.getStatus() == PaymentStatus.SUCCESS) {
            log.error("DISCREPANCY: bank reports FAILED for payment {} already SUCCESS locally ({}). Needs manual review.",
                    payment.getId(), source);
            return Effect.CONFLICT;
        }
        if (payment.getHoldStatus() == HoldStatus.HELD) {
            accountLocker.lock(payment.getPayerAccountId()).releaseReserve(payment.getAmountPaise());
            payment.markHoldReleased();
        }
        payment.recordFailure(code, reason);
        history.save(payment.transition(PaymentStatus.FAILED, source, reason, clock.instant()));
        metrics.failed();
        events.failed(payment, source);
        log.info("Payment {} FAILED ({}) via {}", payment.getId(), code, source);
        return Effect.SETTLED_FAILURE;
    }

    private Effect markPending(Payment payment, TransitionSource source, String reason) {
        if (payment.getStatus() != PaymentStatus.PROCESSING) {
            return Effect.NO_CHANGE; // already PENDING, or already final: never regress
        }
        history.save(payment.transition(PaymentStatus.PENDING, source, reason, clock.instant()));
        metrics.pending();
        events.statusChanged(payment.getId());
        return Effect.MARKED_PENDING;
    }
}
