package com.paysure.payment.application;

import com.paysure.bank.BankContracts.BankSideView;
import com.paysure.cache.PaymentStatusCache;
import com.paysure.bank.BankInspector;
import com.paysure.common.error.NotFoundException;
import com.paysure.identity.application.Caller;
import com.paysure.payment.domain.Payment;
import com.paysure.payment.domain.PaymentAttempt;
import com.paysure.payment.domain.PaymentStateChange;
import com.paysure.payment.domain.PaymentStatus;
import com.paysure.payment.repository.PaymentAttemptRepository;
import com.paysure.payment.repository.PaymentRepository;
import com.paysure.payment.repository.PaymentStateHistoryRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentQueryService {

    private final PaymentRepository payments;
    private final PaymentAttemptRepository attempts;
    private final PaymentStateHistoryRepository history;
    private final ObjectProvider<BankInspector> inspector;
    private final PaymentStatusCache cache;

    public PaymentQueryService(PaymentRepository payments, PaymentAttemptRepository attempts,
                               PaymentStateHistoryRepository history, ObjectProvider<BankInspector> inspector,
                               PaymentStatusCache cache) {
        this.payments = payments;
        this.attempts = attempts;
        this.history = history;
        this.inspector = inspector;
        this.cache = cache;
    }

    /** A payment's status plus whether it came from the Redis cache. */
    public record StatusView(PaymentStatusCache.Entry entry, boolean fromCache) {
    }

    /**
     * Cheap, frequently polled status lookup. Read-through: Redis first, PostgreSQL on a miss or when Redis is down.
     * Authorisation is checked on every call, hit or miss (the cached entry carries the parties).
     */
    @Transactional(readOnly = true)
    public StatusView status(Caller caller, UUID paymentId) {
        var cached = cache.get(paymentId);
        if (cached.isPresent()) {
            PaymentStatusCache.Entry e = cached.get();
            requireVisible(caller, e.payerUserId(), e.payeeUserId());
            return new StatusView(e, true);
        }
        Payment p = payments.findById(paymentId).orElseThrow(() -> new NotFoundException("PAYMENT_NOT_FOUND", "Payment not found."));
        requireVisible(caller, p.getPayerUserId(), p.getPayeeUserId());
        PaymentStatusCache.Entry entry = new PaymentStatusCache.Entry(p.getId(), p.getStatus().name(), p.getHoldStatus().name(),
                p.getFailureCode(), p.getAmountPaise(), p.getPayerUserId(), p.getPayeeUserId(), p.getUpdatedAt());
        cache.put(entry);
        return new StatusView(entry, false);
    }

    private static void requireVisible(Caller caller, UUID payer, UUID payee) {
        if (!caller.isAdminDemo() && !caller.userId().equals(payer) && !caller.userId().equals(payee)) {
            throw new NotFoundException("PAYMENT_NOT_FOUND", "Payment not found.");
        }
    }

    public record PaymentSummary(Payment payment, String bankReference) {
    }

    public record PaymentDetail(Payment payment, List<PaymentStateChange> history, List<PaymentAttempt> attempts,
                                Optional<BankSideView> bankSide) {
    }

    @Transactional(readOnly = true)
    public List<PaymentSummary> list(UUID userId, PaymentStatus status, int limit) {
        List<Payment> page = payments.findForUser(userId, status, PageRequest.of(0, Math.max(1, Math.min(limit, 200))));
        Map<UUID, String> refs = page.isEmpty() ? Map.of() : page.stream()
                .collect(Collectors.toMap(Payment::getId, p -> attempts.findFirstByPaymentIdOrderByAttemptNoDesc(p.getId())
                        .map(PaymentAttempt::getBankReference).orElse(""), (a, b) -> a));
        return page.stream().map(p -> new PaymentSummary(p, emptyToNull(refs.get(p.getId())))).toList();
    }

    /** Visible to the payer, the payee and ADMIN_DEMO; anyone else gets 404 (existence is not revealed). */
    @Transactional(readOnly = true)
    public PaymentDetail detail(Caller caller, UUID paymentId) {
        Payment payment = payments.findById(paymentId)
                .filter(p -> p.involves(caller.userId()) || caller.isAdminDemo())
                .orElseThrow(() -> new NotFoundException("PAYMENT_NOT_FOUND", "Payment not found."));
        List<PaymentAttempt> tries = attempts.findByPaymentIdOrderByAttemptNoAsc(paymentId);
        BankInspector bank = inspector.getIfAvailable();
        Optional<BankSideView> bankSide = tries.isEmpty() || bank == null ? Optional.empty()
                : bank.inspect(tries.get(tries.size() - 1).getBankReference());
        return new PaymentDetail(payment, history.findByPaymentIdOrderByIdAsc(paymentId), tries, bankSide);
    }

    @Transactional(readOnly = true)
    public Payment requireVisible(Caller caller, UUID paymentId) {
        return detail(caller, paymentId).payment();
    }

    @Transactional(readOnly = true)
    public Map<UUID, Payment> byIds(List<UUID> ids) {
        return payments.findAllById(ids).stream().collect(Collectors.toMap(Payment::getId, Function.identity()));
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }
}
