package com.paysure.payment.application;

import com.paysure.cache.PaymentStatusCache;
import com.paysure.events.EventTypes;
import com.paysure.events.OutboxService;
import com.paysure.payment.domain.Payment;
import com.paysure.payment.domain.TransitionSource;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Called from inside the payment transactions. It (a) adds the domain event to the outbox in the SAME transaction as
 * the status change and (b) schedules the status-cache invalidation for after commit.
 */
@Component
public class PaymentEventRecorder {

    private final OutboxService outbox;
    private final PaymentStatusCache cache;
    private final JdbcTemplate jdbc;

    public PaymentEventRecorder(OutboxService outbox, PaymentStatusCache cache, JdbcTemplate jdbc) {
        this.outbox = outbox;
        this.cache = cache;
        this.jdbc = jdbc;
    }

    public void created(Payment p) {
        outbox.record(EventTypes.PAYMENT_CREATED, "PAYMENT", p.getId(), data(p, null));
        cache.invalidateAfterCommit(p.getId());
    }

    public void completed(Payment p, TransitionSource resolvedBy) {
        outbox.record(EventTypes.PAYMENT_COMPLETED, "PAYMENT", p.getId(), data(p, resolvedBy));
        cache.invalidateAfterCommit(p.getId());
    }

    public void failed(Payment p, TransitionSource resolvedBy) {
        outbox.record(EventTypes.PAYMENT_FAILED, "PAYMENT", p.getId(), data(p, resolvedBy));
        cache.invalidateAfterCommit(p.getId());
    }

    /** A status change without a domain event (e.g. PROCESSING → PENDING). */
    public void statusChanged(UUID paymentId) {
        cache.invalidateAfterCommit(paymentId);
    }

    private Map<String, Object> data(Payment p, TransitionSource resolvedBy) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("paymentId", p.getId());
        d.put("status", p.getStatus().name());
        d.put("amountPaise", p.getAmountPaise());
        d.put("currency", "INR");
        d.put("payerVpa", p.getPayerVpa());
        d.put("payeeVpa", p.getPayeeVpa());
        if (p.getNote() != null) {
            d.put("note", p.getNote());
        }
        jdbc.query("select bank_reference from payment_attempts where payment_id = ? order by attempt_no desc limit 1",
                rs -> {
                    d.put("bankReference", rs.getString(1));
                }, p.getId());
        if (p.getFailureCode() != null) {
            d.put("failureCode", p.getFailureCode());
        }
        if (resolvedBy != null) {
            d.put("resolvedBy", resolvedBy.name());
        }
        return d;
    }
}
