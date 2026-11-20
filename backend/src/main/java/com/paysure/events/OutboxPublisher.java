package com.paysure.events;

import com.paysure.events.EventPublisher.Message;
import com.paysure.events.EventPublisher.PublishException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reads outbox rows in commit-independent {@code seq} order and publishes them. Properties:
 * <ul>
 *   <li><b>At-least-once.</b> A row is marked PUBLISHED only after the broker acknowledged it. A crash between the
 *       acknowledgement and the mark republishes the row; consumers deduplicate by event id.</li>
 *   <li><b>Ordered.</b> Rows are published strictly by {@code seq}. On a failure the batch stops, so a later event
 *       can never overtake an earlier one; the publisher then pauses with exponential backoff.</li>
 *   <li><b>One active publisher.</b> A transaction-scoped PostgreSQL advisory lock makes concurrent instances take
 *       turns instead of publishing the same rows (and reordering them).</li>
 *   <li><b>No poison pill.</b> After {@code max-attempts} failures a row is marked DEAD (kept for inspection) so it
 *       cannot block everything behind it.</li>
 * </ul>
 */
@Service
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final long ADVISORY_LOCK_KEY = 7_340_001L;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final EventPublisher publisher;
    private final EventsProperties props;
    private final Clock clock;
    private final Counter publishedCounter;
    private final Counter failureCounter;
    private final Counter deadCounter;

    private volatile Instant pausedUntil = Instant.MIN;
    private volatile int consecutiveFailures;

    public OutboxPublisher(JdbcTemplate jdbc, TransactionTemplate tx, EventPublisher publisher, EventsProperties props,
                           Clock clock, MeterRegistry registry) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.publisher = publisher;
        this.props = props;
        this.clock = clock;
        this.publishedCounter = Counter.builder("outbox.published").description("Outbox events acknowledged by the broker").register(registry);
        this.failureCounter = Counter.builder("outbox.publish.failures").description("Failed outbox publish attempts").register(registry);
        this.deadCounter = Counter.builder("outbox.dead").description("Outbox events given up on").register(registry);
        Gauge.builder("outbox.pending", this, p -> p.pendingCount()).description("Outbox events waiting to be published").register(registry);
    }

    public record Result(int published, int failed, int dead, boolean skipped) {
    }

    private record Row(long seq, UUID id, String eventType, String topic, UUID aggregateId, String payload, int attempts) {
    }

    /** Called by the scheduler: honours the failure backoff. */
    public Result publishIfDue() {
        if (clock.instant().isBefore(pausedUntil)) {
            return new Result(0, 0, 0, true);
        }
        return publishPending();
    }

    /** Publishes up to one batch now (ignores the backoff pause). */
    public Result publishPending() {
        Result result = tx.execute(status -> {
            Boolean locked = jdbc.queryForObject("select pg_try_advisory_xact_lock(?)", Boolean.class, ADVISORY_LOCK_KEY);
            if (!Boolean.TRUE.equals(locked)) {
                return new Result(0, 0, 0, true); // another instance is publishing
            }
            List<Row> rows = jdbc.query("""
                    select seq, id, event_type, topic, aggregate_id, payload, attempts from outbox_events
                    where status = 'PENDING' order by seq limit ?""",
                    (rs, i) -> new Row(rs.getLong("seq"), rs.getObject("id", UUID.class), rs.getString("event_type"),
                            rs.getString("topic"), rs.getObject("aggregate_id", UUID.class), rs.getString("payload"),
                            rs.getInt("attempts")), props.batchSize());
            int published = 0;
            int failed = 0;
            int dead = 0;
            for (Row row : rows) {
                try {
                    publisher.publish(new Message(row.id(), row.eventType(), row.topic(), row.aggregateId().toString(), row.payload()));
                    jdbc.update("update outbox_events set status = 'PUBLISHED', published_at = now(), attempts = attempts + 1, last_error = null where seq = ?",
                            row.seq());
                    published++;
                } catch (PublishException e) {
                    failed++;
                    failureCounter.increment();
                    String error = truncate(e.getMessage());
                    boolean giveUp = row.attempts() + 1 >= props.maxAttempts();
                    jdbc.update("update outbox_events set attempts = attempts + 1, last_error = ?, status = ? where seq = ?",
                            error, giveUp ? "DEAD" : "PENDING", row.seq());
                    if (giveUp) {
                        dead++;
                        deadCounter.increment();
                        log.error("Outbox event {} ({}) given up after {} attempts: {}", row.id(), row.eventType(), row.attempts() + 1, error);
                        continue; // never let one dead row block the ones behind it
                    }
                    log.warn("Outbox publish of {} failed (attempt {}): {} — pausing to preserve ordering", row.id(), row.attempts() + 1, error);
                    break; // keep order: nothing after this row may be published before it
                }
            }
            return new Result(published, failed, dead, false);
        });
        if (result == null || result.skipped()) {
            return result == null ? new Result(0, 0, 0, true) : result;
        }
        publishedCounter.increment(result.published());
        if (result.failed() > result.dead()) {
            consecutiveFailures++;
            long millis = Math.min(props.maxBackoff().toMillis(), 500L << Math.min(consecutiveFailures, 8));
            pausedUntil = clock.instant().plus(Duration.ofMillis(millis));
        } else if (result.failed() == 0) {
            consecutiveFailures = 0;
            pausedUntil = Instant.MIN;
        }
        return result;
    }

    public long pendingCount() {
        Long n = jdbc.queryForObject("select count(*) from outbox_events where status = 'PENDING'", Long.class);
        return n == null ? 0 : n;
    }

    private static String truncate(String s) {
        return s == null ? null : s.length() <= 300 ? s : s.substring(0, 300);
    }
}
