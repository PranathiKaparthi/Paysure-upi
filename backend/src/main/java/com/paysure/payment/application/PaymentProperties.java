package com.paysure.payment.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param maxAmountPaise        per-payment upper bound (₹1,00,000 by default, like a typical UPI limit)
 * @param idempotencyTtl        minimum time an Idempotency-Key is remembered (records are deleted only
 *                              after this AND once their payment is terminal)
 * @param notFoundGrace         how long after submission a bank "no record" answer is still treated as
 *                              "may be in flight" rather than as a definitive failure
 * @param cleanupEnabled        run the scheduled idempotency-record cleanup job
 */
@ConfigurationProperties("paysure.payments")
public record PaymentProperties(
        @DefaultValue("10000000") long maxAmountPaise,
        @DefaultValue("24h") Duration idempotencyTtl,
        @DefaultValue("30s") Duration notFoundGrace,
        @DefaultValue("true") boolean cleanupEnabled) {
}
