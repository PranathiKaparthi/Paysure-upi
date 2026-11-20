package com.paysure.payment.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Business metrics. Labels are deliberately low-cardinality (never user, payment or reference ids).
 * Exposed by Prometheus as e.g. {@code payment_requests_total}, {@code payment_processing_duration_seconds}.
 */
@Component
public class PaymentMetrics {

    private final Counter requests;
    private final Counter success;
    private final Counter failure;
    private final Counter pending;
    private final Counter duplicatesPrevented;
    private final Counter callbacksIgnored;
    private final Timer processing;

    public PaymentMetrics(MeterRegistry registry) {
        requests = Counter.builder("payment.requests").description("New payment intents accepted").register(registry);
        success = Counter.builder("payment.success").description("Payments finalised as SUCCESS").register(registry);
        failure = Counter.builder("payment.failure").description("Payments finalised as FAILED").register(registry);
        pending = Counter.builder("payment.pending").description("Payments that entered PENDING").register(registry);
        duplicatesPrevented = Counter.builder("duplicate.requests.prevented")
                .description("Idempotent replays answered without creating a payment").register(registry);
        callbacksIgnored = Counter.builder("bank.callbacks.ignored")
                .description("Duplicate or stale bank callbacks ignored").register(registry);
        processing = Timer.builder("payment.processing.duration")
                .description("Time from intent to the first bank outcome").publishPercentileHistogram().register(registry);
    }

    public void requested() {
        requests.increment();
    }

    public void succeeded() {
        success.increment();
    }

    public void failed() {
        failure.increment();
    }

    public void pending() {
        pending.increment();
    }

    public void duplicatePrevented() {
        duplicatesPrevented.increment();
    }

    public void callbackIgnored() {
        callbacksIgnored.increment();
    }

    public void processed(Duration d) {
        processing.record(d);
    }
}
