package com.paysure.reliability;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Explainable payment reliability score (0–100).
 *
 * <p><b>This is a configurable demo score, not an industry benchmark.</b> Every input is computed from rows
 * stored by this system (payments, attempts, history, idempotency records); nothing is estimated or invented.
 * A component without data is excluded and the remaining weights are re-normalised, so the score never
 * rewards "no data".
 *
 * <pre>
 *   score = round( Σ(weight_i × score_i) / Σ(weight_i) )   over components that have data
 *
 *   success rate      score = 100 × SUCCESS / (SUCCESS + FAILED)       payments that reached the bank
 *   pending age       score = 100 × (1 − min(1, oldest unresolved age / budget))
 *   completion P95    score = 100 × (1 − clamp((p95 − good) / (bad − good)))      SUCCESS payments, created→completed
 *   timeout rate      score = 100 × (1 − min(1, (timeouts + network errors) / attempts / budget))
 *   recovery rate     score = 100 × resolved / entered PENDING       payments that went PENDING in the window
 *   duplicate safety  score = 100 × (1 − violations / SUCCESS)       violations = SUCCESS without exactly 1 ledger posting
 *   webhook delivery  score = 100 × delivered / (delivered + dead-lettered)    deliveries created in the window
 * </pre>
 */
@Service
public class ReliabilityScoreService {

    private static final Logger log = LoggerFactory.getLogger(ReliabilityScoreService.class);

    public static final String DISCLAIMER = "A configurable demo score computed from this sandbox's own stored events. "
            + "It is not an industry-standard benchmark and says nothing about real-world UPI availability or throughput.";

    private final JdbcTemplate jdbc;
    private final ReliabilityProperties props;
    private final ObjectMapper mapper;
    private final Clock clock;

    public ReliabilityScoreService(JdbcTemplate jdbc, ReliabilityProperties props, ObjectMapper mapper, Clock clock) {
        this.jdbc = jdbc;
        this.props = props;
        this.mapper = mapper;
        this.clock = clock;
    }

    public record Component(String key, String label, int weight, boolean available, Double value, String valueText,
                            Integer score, String explanation) {
    }

    public record Score(Instant windowStart, Instant computedAt, Integer score, List<Component> components,
                        String methodology, String disclaimer) {
    }

    public record Snapshot(UUID id, Instant capturedAt, String trigger, int windowMinutes, int score) {
    }

    public Score compute() {
        return compute(clock.instant().minusSeconds(props.windowMinutes() * 60L));
    }

    /** Computes the score over payments created at or after {@code windowStart}. */
    public Score compute(Instant windowStart) {
        Instant now = clock.instant();
        OffsetDateTime since = odt(windowStart);
        ReliabilityProperties.Weights w = props.weights();
        List<Component> parts = new ArrayList<>();

        // 1. Confirmed payment success rate (payments that actually reached the bank and finished).
        Map<String, Object> sr = jdbc.queryForMap("""
                select count(*) filter (where p.status = 'SUCCESS') as ok, count(*) as total
                from payments p
                where p.created_at >= ? and p.status in ('SUCCESS', 'FAILED')
                  and exists (select 1 from payment_attempts a where a.payment_id = p.id)""", since);
        long ok = num(sr.get("ok"));
        long total = num(sr.get("total"));
        parts.add(total == 0 ? none("success_rate", "Confirmed payment success rate", w.successRate(),
                "No finished bank payments in the window")
                : new Component("success_rate", "Confirmed payment success rate", w.successRate(), true,
                100.0 * ok / total, pct(ok, total) + " (" + ok + " of " + total + ")", (int) Math.round(100.0 * ok / total),
                "Share of payments that reached the bank and ended SUCCESS. Bank declines count against it."));

        // 2. Pending transaction age (oldest unresolved payment right now).
        Map<String, Object> pa = jdbc.queryForMap("""
                select count(*) as total,
                       count(*) filter (where status in ('PROCESSING', 'PENDING')) as n,
                       coalesce(max(extract(epoch from (? - created_at))) filter (where status in ('PROCESSING', 'PENDING')), 0) as oldest
                from payments where created_at >= ?""", odt(now), since);
        long inflight = num(pa.get("n"));
        double oldest = ((Number) pa.get("oldest")).doubleValue();
        double ageScore = 100.0 * (1 - Math.min(1.0, oldest / props.pendingAgeBudgetSeconds()));
        parts.add(num(pa.get("total")) == 0
                ? none("pending_age", "Pending transaction age", w.pendingAge(), "No payments in the window")
                : new Component("pending_age", "Pending transaction age", w.pendingAge(), true, oldest,
                inflight == 0 ? "No unresolved payments" : inflight + " unresolved, oldest " + Math.round(oldest) + " s",
                (int) Math.round(ageScore),
                "Oldest unresolved payment vs a " + props.pendingAgeBudgetSeconds() + " s budget (score 0 at the budget)."));

        // 3. Median and P95 completion latency of successful payments.
        Map<String, Object> lat = jdbc.queryForMap("""
                select count(*) as n,
                       percentile_cont(0.5) within group (order by extract(epoch from (completed_at - created_at))) as p50,
                       percentile_cont(0.95) within group (order by extract(epoch from (completed_at - created_at))) as p95
                from payments where created_at >= ? and status = 'SUCCESS' and completed_at is not null""", since);
        if (num(lat.get("n")) == 0) {
            parts.add(none("latency", "Median / P95 completion latency", w.latency(), "No successful payments in the window"));
        } else {
            double p50 = ((Number) lat.get("p50")).doubleValue();
            double p95 = ((Number) lat.get("p95")).doubleValue();
            double good = props.latencyGoodSeconds();
            double bad = props.latencyBadSeconds();
            double s = 100.0 * (1 - Math.max(0, Math.min(1, (p95 - good) / (bad - good))));
            parts.add(new Component("latency", "Median / P95 completion latency", w.latency(), true, p95,
                    "median " + fmt(p50) + " s · P95 " + fmt(p95) + " s", (int) Math.round(s),
                    "P95 of created→completed time for SUCCESS payments: 100 at ≤ " + good + " s, 0 at ≥ " + bad
                            + " s. Includes time spent PENDING."));
        }

        // 4. Timeout rate (ambiguous bank responses: timeout or network error).
        Map<String, Object> to = jdbc.queryForMap("""
                select count(*) filter (where response_kind in ('TIMEOUT', 'NETWORK_ERROR')) as bad,
                       count(*) filter (where response_kind is not null) as total
                from payment_attempts where created_at >= ?""", since);
        long bad = num(to.get("bad"));
        long answered = num(to.get("total"));
        if (answered == 0) {
            parts.add(none("timeout_rate", "Timeout rate", w.timeoutRate(), "No bank submissions in the window"));
        } else {
            double rate = (double) bad / answered;
            parts.add(new Component("timeout_rate", "Timeout rate", w.timeoutRate(), true, rate,
                    pct(bad, answered) + " (" + bad + " of " + answered + ")",
                    (int) Math.round(100.0 * (1 - Math.min(1.0, rate / props.timeoutRateBudget()))),
                    "Timeouts and network errors per bank submission; score 0 at " + Math.round(props.timeoutRateBudget() * 100) + " %."));
        }

        // 5. Reconciliation recovery rate (of payments that went PENDING, how many reached a final state).
        Map<String, Object> rec = jdbc.queryForMap("""
                select count(*) as entered, count(*) filter (where p.status in ('SUCCESS', 'FAILED')) as recovered,
                       count(*) filter (where p.status in ('SUCCESS', 'FAILED') and exists (
                           select 1 from payment_state_history h where h.payment_id = p.id and h.source = 'RECONCILIATION')) as by_recon
                from payments p
                where p.created_at >= ? and exists (select 1 from payment_state_history h where h.payment_id = p.id and h.to_status = 'PENDING')""",
                since);
        long entered = num(rec.get("entered"));
        long recovered = num(rec.get("recovered"));
        parts.add(entered == 0 ? none("recovery_rate", "Reconciliation recovery rate", w.recoveryRate(),
                "No payment went PENDING in the window")
                : new Component("recovery_rate", "Reconciliation recovery rate", w.recoveryRate(), true,
                100.0 * recovered / entered, pct(recovered, entered) + " (" + recovered + " of " + entered
                + " recovered, " + num(rec.get("by_recon")) + " by reconciliation)",
                (int) Math.round(100.0 * recovered / entered),
                "Of payments that entered PENDING, the share that reached SUCCESS or FAILED (via callback, check or reconciliation)."));

        // 6. Duplicate-processing prevention: an invariant check plus a count of duplicates absorbed.
        Map<String, Object> dup = jdbc.queryForMap("""
                select
                  (select count(*) from payments p where p.created_at >= ? and p.status = 'SUCCESS'
                     and (select count(*) from ledger_journals j where j.journal_type = 'PAYMENT_TRANSFER' and j.reference_id = p.id) <> 1) as violations,
                  (select count(*) from payments p where p.created_at >= ? and p.status = 'SUCCESS') as successes,
                  (select coalesce(sum(i.replay_count), 0) from idempotency_records i where i.created_at >= ?) as replays,
                  (select coalesce(sum(a.callbacks_ignored), 0) from payment_attempts a where a.created_at >= ?) as ignored""",
                since, since, since, since);
        long violations = num(dup.get("violations"));
        long successes = num(dup.get("successes"));
        long absorbed = num(dup.get("replays")) + num(dup.get("ignored"));
        parts.add(successes == 0 ? none("duplicate_prevention", "Duplicate-processing prevention", w.duplicatePrevention(),
                "No successful payments in the window")
                : new Component("duplicate_prevention", "Duplicate-processing prevention", w.duplicatePrevention(), true,
                (double) violations, violations + " double/missing postings · " + absorbed + " duplicate signals absorbed ("
                + num(dup.get("replays")) + " idempotent replays, " + num(dup.get("ignored")) + " duplicate/stale callbacks)",
                (int) Math.round(100.0 * (1 - Math.min(1.0, (double) violations / successes))),
                "Every SUCCESS payment must have exactly one ledger posting. Duplicates absorbed are shown for context."));

        // 7. Webhook delivery success: of deliveries that reached a final state, the share delivered (not dead-lettered).
        Map<String, Object> wh = jdbc.queryForMap("""
                select count(*) filter (where status = 'SUCCESS') as ok, count(*) filter (where status = 'DEAD') as dead,
                       count(*) filter (where status = 'SUCCESS' and attempts > 1) as retried
                from webhook_deliveries where created_at >= ?""", since);
        long whOk = num(wh.get("ok"));
        long whDead = num(wh.get("dead"));
        parts.add(whOk + whDead == 0
                ? none("webhook_delivery", "Webhook delivery success rate", w.webhookDelivery(), "No finished webhook deliveries in the window")
                : new Component("webhook_delivery", "Webhook delivery success rate", w.webhookDelivery(), true,
                100.0 * whOk / (whOk + whDead), pct(whOk, whOk + whDead) + " (" + whOk + " delivered, " + whDead
                + " dead-lettered, " + num(wh.get("retried")) + " needed retries)", (int) Math.round(100.0 * whOk / (whOk + whDead)),
                "Deliveries that finished: delivered ÷ (delivered + dead-lettered). Retries that eventually succeed count as delivered."));

        int weightSum = parts.stream().filter(Component::available).mapToInt(Component::weight).sum();
        Integer overall = weightSum == 0 ? null : (int) Math.round(parts.stream().filter(Component::available)
                .mapToDouble(c -> (double) c.weight() * c.score()).sum() / weightSum);
        return new Score(windowStart, now, overall, parts, METHODOLOGY, DISCLAIMER);
    }

    public static final String METHODOLOGY = "score = Σ(weight × component score) ÷ Σ(weight) over components that have data; "
            + "each component is scored 0–100 as described on its row. Weights and budgets are configurable (paysure.reliability.*).";

    // ---- snapshots -----------------------------------------------------------------------------------------

    /** Stores the current score so the history chart can show it moving after fault injections and recovery. */
    public Snapshot captureSnapshot(String trigger) {
        Score s = compute();
        if (s.score() == null) {
            return null;
        }
        UUID id = UUID.randomUUID();
        String json;
        try {
            json = mapper.writeValueAsString(s.components());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        jdbc.update("""
                insert into reliability_snapshots (id, captured_at, trigger_type, window_minutes, score, components)
                values (?, ?, ?, ?, ?, ?)""", id, odt(s.computedAt()), trigger, props.windowMinutes(), s.score(), json);
        return new Snapshot(id, s.computedAt(), trigger, props.windowMinutes(), s.score());
    }

    public List<Snapshot> history(int limit) {
        List<Snapshot> rows = jdbc.query("""
                select id, captured_at, trigger_type, window_minutes, score from reliability_snapshots
                order by captured_at desc limit ?""", (rs, i) -> new Snapshot(rs.getObject("id", UUID.class),
                rs.getObject("captured_at", OffsetDateTime.class).toInstant(), rs.getString("trigger_type"),
                rs.getInt("window_minutes"), rs.getInt("score")), Math.max(1, Math.min(limit, 500)));
        List<Snapshot> chronological = new ArrayList<>(rows);
        java.util.Collections.reverse(chronological);
        return chronological;
    }

    @Scheduled(fixedDelayString = "${paysure.reliability.snapshot-interval-millis:300000}", initialDelay = 60000)
    void scheduledSnapshot() {
        if (!props.snapshotSchedulerEnabled()) {
            return;
        }
        try {
            captureSnapshot("SCHEDULED");
        } catch (RuntimeException e) {
            log.error("Scheduled reliability snapshot failed", e);
        }
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    private static Component none(String key, String label, int weight, String why) {
        return new Component(key, label, weight, false, null, why, null, why);
    }

    private static long num(Object o) {
        return o == null ? 0 : ((Number) o).longValue();
    }

    private static String pct(long a, long b) {
        return String.format(java.util.Locale.ROOT, "%.1f %%", 100.0 * a / b);
    }

    private static String fmt(double d) {
        return String.format(java.util.Locale.ROOT, "%.1f", d);
    }

    private static OffsetDateTime odt(Instant i) {
        return OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
    }
}
