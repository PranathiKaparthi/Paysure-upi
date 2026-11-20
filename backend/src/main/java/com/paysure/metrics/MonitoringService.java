package com.paysure.metrics;

import com.paysure.bank.simulator.SimulatedBank;
import com.paysure.ratelimit.RedisGuard;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Figures for the System Monitoring page. Everything is computed from stored rows (so it survives restarts) or read from the
 * Micrometer registry (process-local counters, labelled "since process start"). Nothing is fabricated: a dependency that cannot be
 * probed directly is reported as inferred, and says so.
 */
@Service
public class MonitoringService {

    private final JdbcTemplate jdbc;
    private final MeterRegistry registry;
    private final RedisGuard redisGuard;
    private final StringRedisTemplate redis;
    private final SimulatedBank bank;
    private final Clock clock;

    public MonitoringService(JdbcTemplate jdbc, MeterRegistry registry, RedisGuard redisGuard, StringRedisTemplate redis,
                             SimulatedBank bank, Clock clock) {
        this.jdbc = jdbc;
        this.registry = registry;
        this.redisGuard = redisGuard;
        this.redis = redis;
        this.bank = bank;
        this.clock = clock;
    }

    public record Dependency(String name, String status, String detail) {
    }

    public Map<String, Object> summary() {
        Instant now = clock.instant();
        OffsetDateTime since24h = ts(now.minus(Duration.ofHours(24)));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("generatedAt", now);

        Map<String, Object> pay = jdbc.queryForMap("""
                select count(*) as total,
                       count(*) filter (where status = 'SUCCESS') as success,
                       count(*) filter (where status = 'FAILED') as failed,
                       count(*) filter (where status in ('PROCESSING', 'PENDING')) as unfinished,
                       percentile_cont(0.5) within group (order by extract(epoch from (completed_at - created_at)) * 1000)
                           filter (where status = 'SUCCESS' and completed_at is not null) as p50,
                       percentile_cont(0.95) within group (order by extract(epoch from (completed_at - created_at)) * 1000)
                           filter (where status = 'SUCCESS' and completed_at is not null) as p95
                from payments where created_at >= ?""", since24h);
        long finished = num(pay.get("success")) + num(pay.get("failed"));
        Map<String, Object> payments = new LinkedHashMap<>();
        payments.put("windowHours", 24);
        payments.put("total", num(pay.get("total")));
        payments.put("success", num(pay.get("success")));
        payments.put("failed", num(pay.get("failed")));
        payments.put("unfinished", num(pay.get("unfinished")));
        payments.put("successRate", finished == 0 ? null : (double) num(pay.get("success")) / finished);
        payments.put("p50Ms", pay.get("p50") == null ? null : Math.round(((Number) pay.get("p50")).doubleValue()));
        payments.put("p95Ms", pay.get("p95") == null ? null : Math.round(((Number) pay.get("p95")).doubleValue()));
        Map<String, Object> oldest = jdbc.queryForMap("""
                select count(*) as n, coalesce(max(extract(epoch from (? - created_at))), 0) as age
                from payments where status in ('PROCESSING', 'PENDING')""", ts(now));
        payments.put("pendingNow", num(oldest.get("n")));
        payments.put("oldestPendingSeconds", Math.round(((Number) oldest.get("age")).doubleValue()));
        out.put("payments", payments);

        Map<String, Object> counters = new LinkedHashMap<>();
        counters.put("paymentRequests", sum("payment.requests"));
        counters.put("paymentSuccess", sum("payment.success"));
        counters.put("paymentFailure", sum("payment.failure"));
        counters.put("paymentPending", sum("payment.pending"));
        counters.put("duplicateRequestsPrevented", sum("duplicate.requests.prevented"));
        counters.put("callbacksIgnored", sum("bank.callbacks.ignored"));
        counters.put("reconciliationRuns", sum("reconciliation.runs"));
        counters.put("reconciliationResolved", sum("reconciliation.resolved"));
        counters.put("webhookDeliveryFailures", sum("webhook.delivery.failures"));
        counters.put("outboxPublished", sum("outbox.published"));
        counters.put("outboxPublishFailures", sum("outbox.publish.failures"));
        counters.put("rateLimited", sum("ratelimit.rejected"));
        counters.put("redisErrors", sum("redis.errors"));
        counters.put("riskAlerts", sum("risk.alerts"));
        counters.put("riskBlocked", sum("risk.blocked"));
        counters.put("statusCacheHits", tagged("payment.status.cache", "result", "hit"));
        counters.put("statusCacheMisses", tagged("payment.status.cache", "result", "miss"));
        out.put("countersSinceProcessStart", counters);

        Map<String, Object> pipeline = jdbc.queryForMap("""
                select (select count(*) from outbox_events where status = 'PENDING') as outbox_pending,
                       (select count(*) from outbox_events where status = 'DEAD') as outbox_dead,
                       (select coalesce(max(extract(epoch from (? - created_at))), 0) from outbox_events where status = 'PENDING') as oldest,
                       (select max(published_at) from outbox_events) as last_published,
                       (select count(*) from webhook_deliveries where status in ('PENDING', 'RETRYING', 'DELIVERING')) as deliveries_open,
                       (select count(*) from webhook_deliveries where status = 'DEAD') as deliveries_dead""", ts(now));
        Map<String, Object> pipe = new LinkedHashMap<>();
        pipe.put("outboxPending", num(pipeline.get("outbox_pending")));
        pipe.put("outboxDead", num(pipeline.get("outbox_dead")));
        pipe.put("oldestPendingEventSeconds", Math.round(((Number) pipeline.get("oldest")).doubleValue()));
        pipe.put("lastPublishedAt", instant(pipeline.get("last_published")));
        pipe.put("webhookDeliveriesOpen", num(pipeline.get("deliveries_open")));
        pipe.put("webhookDeliveriesDead", num(pipeline.get("deliveries_dead")));
        out.put("pipeline", pipe);

        Map<String, Object> risk = jdbc.queryForMap("""
                select count(*) filter (where status = 'OPEN') as open,
                       count(*) filter (where created_at >= ? and level = 'HIGH') as high,
                       count(*) filter (where created_at >= ? and level = 'MEDIUM') as medium,
                       count(*) filter (where created_at >= ? and blocked) as blocked
                from risk_alerts""", since24h, since24h, since24h);
        out.put("risk", Map.of("open", num(risk.get("open")), "high24h", num(risk.get("high")),
                "medium24h", num(risk.get("medium")), "blocked24h", num(risk.get("blocked"))));

        out.put("dependencies", dependencies(now, num(pipeline.get("outbox_pending")),
                ((Number) pipeline.get("oldest")).doubleValue()));
        return out;
    }

    /** Thirty one-minute buckets ending now. Payments are bucketed by creation minute and show their CURRENT status. */
    public Map<String, Object> series() {
        OffsetDateTime now = ts(clock.instant());
        List<Map<String, Object>> payments = jdbc.query("""
                select g.t, count(p.id) as created,
                       count(p.id) filter (where p.status = 'SUCCESS') as success,
                       count(p.id) filter (where p.status = 'FAILED') as failed,
                       count(p.id) filter (where p.status in ('PROCESSING', 'PENDING')) as unfinished
                from generate_series(date_trunc('minute', ?::timestamptz) - interval '29 minutes',
                                     date_trunc('minute', ?::timestamptz), interval '1 minute') as g(t)
                left join payments p on p.created_at >= g.t and p.created_at < g.t + interval '1 minute'
                group by g.t order by g.t""", (rs, i) -> bucket(rs, "created", "success", "failed", "unfinished"), now, now);
        List<Map<String, Object>> webhooks = jdbc.query("""
                select g.t, count(a.id) filter (where a.outcome = 'SUCCESS') as delivered,
                       count(a.id) filter (where a.outcome <> 'SUCCESS') as failed
                from generate_series(date_trunc('minute', ?::timestamptz) - interval '29 minutes',
                                     date_trunc('minute', ?::timestamptz), interval '1 minute') as g(t)
                left join webhook_delivery_attempts a on a.started_at >= g.t and a.started_at < g.t + interval '1 minute'
                group by g.t order by g.t""", (rs, i) -> bucket(rs, "delivered", "failed"), now, now);
        List<Map<String, Object>> recon = jdbc.query("""
                select g.t, count(i.id) filter (where i.outcome = 'RESOLVED') as resolved,
                       count(i.id) filter (where i.outcome in ('STILL_PENDING', 'UNKNOWN', 'ESCALATED')) as unresolved,
                       count(i.id) filter (where i.outcome in ('CONFLICT', 'LEDGER_MISMATCH')) as discrepancies
                from generate_series(date_trunc('minute', ?::timestamptz) - interval '29 minutes',
                                     date_trunc('minute', ?::timestamptz), interval '1 minute') as g(t)
                left join reconciliation_items i on i.created_at >= g.t and i.created_at < g.t + interval '1 minute'
                group by g.t order by g.t""", (rs, i) -> bucket(rs, "resolved", "unresolved", "discrepancies"), now, now);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("payments", payments);
        out.put("webhooks", webhooks);
        out.put("reconciliation", recon);
        return out;
    }

    private List<Dependency> dependencies(Instant now, long outboxPending, double oldestPendingSeconds) {
        List<Dependency> deps = new ArrayList<>();
        try {
            jdbc.queryForObject("select 1", Integer.class);
            deps.add(new Dependency("PostgreSQL", "UP", "System of record; query answered."));
        } catch (RuntimeException e) {
            deps.add(new Dependency("PostgreSQL", "DOWN", e.getClass().getSimpleName()));
        }
        if (!redisGuard.available()) {
            deps.add(new Dependency("Redis", "DEGRADED", "Recent errors: using in-memory rate limits and database reads."));
        } else {
            try {
                String pong = redis.execute((org.springframework.data.redis.core.RedisCallback<String>) c -> c.ping());
                deps.add(new Dependency("Redis", "UP", "PING " + pong + ". Rate limits and status cache are served from Redis."));
            } catch (RuntimeException e) {
                redisGuard.failure(e);
                deps.add(new Dependency("Redis", "DEGRADED", "Probe failed: " + e.getClass().getSimpleName() + "; fallbacks active."));
            }
        }
        boolean lagging = outboxPending > 0 && oldestPendingSeconds > 30;
        deps.add(new Dependency("Kafka (inferred from the outbox)", lagging ? "DEGRADED" : "UP",
                lagging ? outboxPending + " event(s) waiting, oldest " + Math.round(oldestPendingSeconds) + " s: the broker may be unreachable. Payments are unaffected."
                        : "Outbox is draining (" + outboxPending + " waiting). Not a direct broker probe."));
        deps.add(new Dependency("Simulated bank status API", bank.isStatusApiAvailable() ? "UP" : "DOWN",
                bank.isStatusApiAvailable() ? "Reconciliation can verify payments." : "Outage switched on in the Reliability Lab: outcomes cannot be verified."));
        var last = jdbc.queryForList("select started_at, status from reconciliation_runs order by started_at desc limit 1");
        if (last.isEmpty()) {
            deps.add(new Dependency("Reconciliation worker", "DEGRADED", "No run recorded yet."));
        } else {
            Instant at = instant(last.get(0).get("started_at"));
            long age = Duration.between(at, now).getSeconds();
            deps.add(new Dependency("Reconciliation worker", age <= 120 ? "UP" : "DEGRADED",
                    "Last run " + age + " s ago (" + last.get(0).get("status") + ")."));
        }
        return deps;
    }

    private double sum(String name) {
        return registry.find(name).counters().stream().mapToDouble(Counter::count).sum();
    }

    private double tagged(String name, String tag, String value) {
        return registry.find(name).tag(tag, value).counters().stream().mapToDouble(Counter::count).sum();
    }

    private static Map<String, Object> bucket(java.sql.ResultSet rs, String... cols) throws java.sql.SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("t", rs.getObject("t", OffsetDateTime.class).toInstant());
        for (String c : cols) {
            m.put(c, rs.getLong(c));
        }
        return m;
    }

    private static long num(Object o) {
        return o == null ? 0 : ((Number) o).longValue();
    }

    private static Instant instant(Object o) {
        if (o instanceof java.sql.Timestamp t) {
            return t.toInstant();
        }
        if (o instanceof OffsetDateTime t) {
            return t.toInstant();
        }
        return null;
    }

    private static OffsetDateTime ts(Instant i) {
        return OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
    }
}
