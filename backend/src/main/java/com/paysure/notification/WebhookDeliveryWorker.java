package com.paysure.notification;

import com.paysure.common.error.BadRequestException;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Delivers webhooks with at-least-once semantics: claim (lease, {@code SKIP LOCKED}) → sign → POST → record the attempt
 * and the next state. Failures are retried with bounded exponential backoff and jitter; after {@code max-attempts}
 * the delivery is dead-lettered (status DEAD, kept for inspection and manual retry).
 *
 * <p>Receivers may see the same event more than once (a retry after a lost response, or a re-claimed lease), so each
 * request carries a stable event id; the receiver must deduplicate by it. Webhooks only <em>report</em> what already
 * happened — a retry can never cause money to move.
 */
@Service
public class WebhookDeliveryWorker {

    private static final Logger log = LoggerFactory.getLogger(WebhookDeliveryWorker.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final WebhookTransport transport;
    private final SsrfGuard ssrf;
    private final WebhookEndpointService endpoints;
    private final BackoffPolicy backoff;
    private final WebhookProperties props;
    private final Clock clock;
    private final MeterRegistry registry;

    public WebhookDeliveryWorker(JdbcTemplate jdbc, TransactionTemplate tx, WebhookTransport transport, SsrfGuard ssrf,
                                 WebhookEndpointService endpoints, BackoffPolicy backoff, WebhookProperties props,
                                 Clock clock, MeterRegistry registry) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.transport = transport;
        this.ssrf = ssrf;
        this.endpoints = endpoints;
        this.backoff = backoff;
        this.props = props;
        this.clock = clock;
        this.registry = registry;
        // Register up front so the series exist (at 0) before the first delivery; dashboards and alerts need that.
        registry.counter("webhook.delivery.failures");
        for (String outcome : List.of("success", "retry", "dead")) {
            registry.counter("webhook.deliveries", "outcome", outcome);
        }
    }

    public record Result(int attempted, int succeeded, int retrying, int dead) {
    }

    private record Claimed(UUID id, UUID endpointId, UUID eventId, String eventType, String payload, int attempts, int maxAttempts) {
    }

    private enum Outcome { SUCCESS, RETRY, DEAD }

    /** @param now decides which deliveries are due and when retries are scheduled (tests pass a later instant) */
    public Result deliverDue(Instant now) {
        List<Claimed> claimed = jdbc.query("""
                update webhook_deliveries set status = 'DELIVERING', lease_until = ?
                where id in (
                    select id from webhook_deliveries
                    where (status in ('PENDING', 'RETRYING') and next_attempt_at <= ?)
                       or (status = 'DELIVERING' and lease_until < ?)
                    order by next_attempt_at
                    limit ?
                    for update skip locked)
                returning id, endpoint_id, event_id, event_type, payload, attempts, max_attempts""",
                (rs, i) -> new Claimed(rs.getObject("id", UUID.class), rs.getObject("endpoint_id", UUID.class),
                        rs.getObject("event_id", UUID.class), rs.getString("event_type"), rs.getString("payload"),
                        rs.getInt("attempts"), rs.getInt("max_attempts")),
                ts(now.plus(props.lease())), ts(now), ts(now), props.batchSize());
        int ok = 0;
        int retry = 0;
        int dead = 0;
        for (Claimed c : claimed) {
            switch (deliverOne(c, now)) {
                case SUCCESS -> ok++;
                case RETRY -> retry++;
                case DEAD -> dead++;
            }
        }
        return new Result(claimed.size(), ok, retry, dead);
    }

    private Outcome deliverOne(Claimed c, Instant now) {
        Instant started = clock.instant();
        int attemptNo = c.attempts() + 1;
        var rows = jdbc.queryForList("select url, enabled, secret_version from webhook_endpoints where id = ?", c.endpointId());
        if (rows.isEmpty() || !(Boolean) rows.get(0).get("enabled")) {
            return finish(c, attemptNo, started, now, null, "Endpoint is disabled", true);
        }
        URI uri;
        try {
            uri = ssrf.validate((String) rows.get(0).get("url"));
        } catch (BadRequestException e) {
            // Deterministic: retrying cannot help, so dead-letter at once.
            return finish(c, attemptNo, started, now, null, "Blocked by SSRF policy: " + e.getMessage(), true);
        }
        long timestamp = started.getEpochSecond();
        String secret = endpoints.secretFor(c.endpointId(), ((Number) rows.get(0).get("secret_version")).intValue());
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", "PaySure-Webhooks/1");
        headers.put(WebhookSigner.EVENT_ID_HEADER, c.eventId().toString());
        headers.put(WebhookSigner.EVENT_TYPE_HEADER, c.eventType());
        headers.put(WebhookSigner.DELIVERY_ID_HEADER, c.id().toString());
        headers.put(WebhookSigner.ENDPOINT_ID_HEADER, c.endpointId().toString());
        headers.put(WebhookSigner.TIMESTAMP_HEADER, Long.toString(timestamp));
        headers.put(WebhookSigner.SIGNATURE_HEADER, WebhookSigner.sign(secret, timestamp, c.payload()));

        WebhookTransport.Result result;
        try {
            result = transport.send(uri, headers, c.payload());
        } catch (RuntimeException e) {
            result = new WebhookTransport.Result(null, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        String error = result.success() ? null : result.statusCode() != null ? "HTTP " + result.statusCode() : result.error();
        return finish(c, attemptNo, started, now, result.statusCode(), error, false);
    }

    /** Records the attempt and moves the delivery to its next state in one transaction. */
    private Outcome finish(Claimed c, int attemptNo, Instant started, Instant now, Integer statusCode, String error,
                           boolean nonRetryable) {
        boolean success = error == null;
        Outcome outcome = success ? Outcome.SUCCESS : nonRetryable || attemptNo >= c.maxAttempts() ? Outcome.DEAD : Outcome.RETRY;
        long durationMs = Math.max(0, Duration.between(started, clock.instant()).toMillis());
        Instant next = outcome == Outcome.RETRY ? now.plus(backoff.delayAfter(attemptNo)) : now;
        String trimmed = error == null ? null : error.length() > 300 ? error.substring(0, 300) : error;
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    insert into webhook_delivery_attempts (delivery_id, attempt_no, started_at, duration_ms, status_code, error, outcome)
                    values (?, ?, ?, ?, ?, ?, ?)""", c.id(), attemptNo, ts(started), durationMs, statusCode, trimmed, outcome.name());
            jdbc.update("""
                    update webhook_deliveries set status = ?, attempts = ?, lease_until = null, last_status_code = ?,
                        last_error = ?, last_attempt_at = ?, next_attempt_at = ?, delivered_at = ?
                    where id = ?""",
                    switch (outcome) { case SUCCESS -> "SUCCESS"; case RETRY -> "RETRYING"; case DEAD -> "DEAD"; },
                    attemptNo, statusCode, trimmed, ts(started), ts(next), success ? ts(clock.instant()) : null, c.id());
        });
        registry.counter("webhook.deliveries", "outcome", outcome.name().toLowerCase()).increment();
        if (!success) {
            registry.counter("webhook.delivery.failures").increment();
            log.warn("Webhook delivery {} attempt {} failed: {} -> {}", c.id(), attemptNo, trimmed, outcome);
        }
        return outcome;
    }

    private static OffsetDateTime ts(Instant i) {
        return OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
    }
}
