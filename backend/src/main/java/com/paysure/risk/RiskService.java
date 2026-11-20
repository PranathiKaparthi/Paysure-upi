package com.paysure.risk;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paysure.common.error.BusinessRuleException;
import com.paysure.common.error.NotFoundException;
import com.paysure.events.EventTypes;
import com.paysure.events.OutboxService;
import com.paysure.risk.RiskEngine.Assessment;
import com.paysure.risk.RiskEngine.Facts;
import com.paysure.risk.RiskEngine.RuleHit;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Gathers the facts about a payment from PostgreSQL, runs the {@link RiskEngine}, and records alerts.
 *
 * <p>Alerts for payments that go ahead are written in the payment's own transaction together with a {@code risk.alert}
 * outbox event. A payment refused in ENFORCE mode rolls back, so its alert is written in a separate transaction
 * (otherwise the evidence would disappear with the rollback).
 */
@Service
public class RiskService {

    private static final Logger log = LoggerFactory.getLogger(RiskService.class);
    public static final String DISCLAIMER = "Risk alerts are educational simulations produced by transparent rules. "
            + "They are not real fraud determinations, and no machine-learning model is used.";

    private final JdbcTemplate jdbc;
    private final OutboxService outbox;
    private final TransactionTemplate requiresNew;
    private final RiskProperties props;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final MeterRegistry registry;

    public RiskService(JdbcTemplate jdbc, OutboxService outbox, org.springframework.transaction.PlatformTransactionManager txManager,
                       RiskProperties props, ObjectMapper mapper, Clock clock, MeterRegistry registry) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.requiresNew = new TransactionTemplate(txManager);
        this.requiresNew.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.props = props;
        this.mapper = mapper;
        this.clock = clock;
        this.registry = registry;
        // Register the series up front so dashboards show 0 rather than "no data".
        for (String level : List.of("low", "medium", "high")) {
            registry.counter("risk.evaluations", "level", level);
            registry.counter("risk.alerts", "level", level);
        }
        registry.counter("risk.blocked");
    }

    public RiskProperties properties() {
        return props;
    }

    public boolean enabled() {
        return props.enabled();
    }

    /** Evaluates a prospective payment from {@code payerUserId} to {@code payeeAccountId}. Read-only. */
    public Assessment assess(UUID payerUserId, UUID payeeAccountId, long amountPaise) {
        Instant now = clock.instant();
        Map<String, Object> row = jdbc.queryForMap("""
                select count(*) filter (where created_at >= ?) as velocity,
                       count(*) filter (where created_at >= ? and payee_account_id = ?) as repeats,
                       count(*) filter (where created_at >= ? and status = 'FAILED') as failed,
                       count(*) filter (where created_at >= ?) as burst
                from payments where payer_user_id = ? and created_at >= ?""",
                ts(now.minusSeconds(props.velocityWindowSeconds())),
                ts(now.minusSeconds(props.repeatWindowSeconds())), payeeAccountId,
                ts(now.minusSeconds(props.failureWindowSeconds())),
                ts(now.minusSeconds(props.burstWindowSeconds())),
                payerUserId,
                ts(now.minusSeconds(Math.max(Math.max(props.velocityWindowSeconds(), props.repeatWindowSeconds()),
                        Math.max(props.failureWindowSeconds(), props.burstWindowSeconds())))));
        Boolean known = jdbc.queryForObject("""
                select exists (select 1 from payments where payer_user_id = ? and payee_account_id = ? and status = 'SUCCESS')""",
                Boolean.class, payerUserId, payeeAccountId);
        // Counts from the database exclude the payment being evaluated, so add it.
        Facts facts = new Facts(amountPaise, num(row, "velocity") + 1, num(row, "repeats") + 1, num(row, "failed"),
                !Boolean.TRUE.equals(known), num(row, "burst") + 1);
        Assessment a = RiskEngine.evaluate(facts, props);
        registry.counter("risk.evaluations", "level", a.level().name().toLowerCase()).increment();
        return a;
    }

    public boolean shouldBlock(Assessment a) {
        return props.enabled() && props.mode() == RiskProperties.Mode.ENFORCE && a.score() >= props.blockScore();
    }

    /** Records an alert for a payment that proceeds. Joins the caller's transaction (and its outbox event). */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID recordAlert(Assessment a, UUID userId, UUID paymentId, String payerVpa, String payeeVpa, long amountPaise) {
        return insert(a, userId, paymentId, payerVpa, payeeVpa, amountPaise, false);
    }

    /** Records the alert of a REFUSED payment in its own transaction so it survives the caller's rollback. */
    public UUID recordBlocked(Assessment a, UUID userId, String payerVpa, String payeeVpa, long amountPaise) {
        UUID id = requiresNew.execute(s -> insert(a, userId, null, payerVpa, payeeVpa, amountPaise, true));
        registry.counter("risk.blocked").increment();
        return id;
    }

    private UUID insert(Assessment a, UUID userId, UUID paymentId, String payerVpa, String payeeVpa, long amountPaise, boolean blocked) {
        UUID id = UUID.randomUUID();
        String rules;
        try {
            rules = mapper.writeValueAsString(a.rules());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        jdbc.update("""
                insert into risk_alerts (id, user_id, payment_id, payer_vpa, payee_vpa, amount_paise, score, level, action, blocked,
                                         rules, explanation, suggested_action)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                id, userId, paymentId, payerVpa, payeeVpa, amountPaise, a.score(), a.level().name(), a.action().name(), blocked,
                rules, String.join("\n", a.explanation()), a.suggestedAction());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("alertId", id);
        data.put("userId", userId); // routes the webhook to the affected user (and demo admins)
        if (paymentId != null) {
            data.put("paymentId", paymentId);
        }
        data.put("score", a.score());
        data.put("level", a.level().name());
        data.put("action", a.action().name());
        data.put("blocked", blocked);
        data.put("amountPaise", amountPaise);
        data.put("rules", a.rules().stream().map(RuleHit::code).toList());
        outbox.record(EventTypes.RISK_ALERT, "RISK_ALERT", id, data);
        registry.counter("risk.alerts", "level", a.level().name().toLowerCase()).increment();
        log.info("Risk alert {} level={} score={} blocked={}", id, a.level(), a.score(), blocked);
        return id;
    }

    /** Links an alert created before the payment row existed (kept for callers that record it earlier). */
    public void linkPayment(UUID alertId, UUID paymentId) {
        jdbc.update("update risk_alerts set payment_id = ? where id = ? and payment_id is null and not blocked", paymentId, alertId);
    }

    // ---- read side ---------------------------------------------------------------------------------------------

    public record Alert(UUID id, UUID userId, UUID paymentId, String payerVpa, String payeeVpa, long amountPaise, int score,
                        String level, String action, boolean blocked, List<RuleHit> rules, List<String> explanation,
                        String suggestedAction, String status, Instant createdAt, Instant reviewedAt) {
    }

    /** @param userId restrict to one user (null = all, for operators) */
    public List<Alert> list(UUID userId, String status, UUID paymentId, int limit) {
        StringBuilder sql = new StringBuilder("select * from risk_alerts where true");
        List<Object> args = new ArrayList<>();
        if (userId != null) {
            sql.append(" and user_id = ?");
            args.add(userId);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" and status = ?");
            args.add(status.toUpperCase());
        }
        if (paymentId != null) {
            sql.append(" and payment_id = ?");
            args.add(paymentId);
        }
        sql.append(" order by created_at desc, id limit ?");
        args.add(Math.max(1, Math.min(limit, 200)));
        return jdbc.query(sql.toString(), (rs, i) -> map(rs), args.toArray());
    }

    @Transactional
    public Alert review(UUID alertId, UUID reviewerId, String newStatus) {
        if (!"ACKNOWLEDGED".equals(newStatus) && !"DISMISSED".equals(newStatus)) {
            throw new BusinessRuleException("INVALID_REVIEW_STATUS", "Status must be ACKNOWLEDGED or DISMISSED.");
        }
        var rows = jdbc.queryForList("select status from risk_alerts where id = ? for update", String.class, alertId);
        if (rows.isEmpty()) {
            throw new NotFoundException("RISK_ALERT_NOT_FOUND", "Risk alert not found.");
        }
        if (!"OPEN".equals(rows.get(0))) {
            throw new BusinessRuleException("ALERT_ALREADY_REVIEWED", "This alert was already " + rows.get(0) + ".");
        }
        jdbc.update("update risk_alerts set status = ?, reviewed_by = ?, reviewed_at = ? where id = ?", newStatus, reviewerId,
                ts(clock.instant()), alertId);
        return jdbc.query("select * from risk_alerts where id = ?", (rs, i) -> map(rs), alertId).get(0);
    }

    private Alert map(java.sql.ResultSet rs) throws java.sql.SQLException {
        List<RuleHit> rules;
        try {
            rules = mapper.readValue(rs.getString("rules"), mapper.getTypeFactory().constructCollectionType(List.class, RuleHit.class));
        } catch (JsonProcessingException e) {
            rules = List.of();
        }
        OffsetDateTime reviewed = rs.getObject("reviewed_at", OffsetDateTime.class);
        return new Alert(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class), rs.getObject("payment_id", UUID.class),
                rs.getString("payer_vpa"), rs.getString("payee_vpa"), rs.getLong("amount_paise"), rs.getInt("score"),
                rs.getString("level"), rs.getString("action"), rs.getBoolean("blocked"), rules,
                List.of(rs.getString("explanation").split("\n")), rs.getString("suggested_action"), rs.getString("status"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(), reviewed == null ? null : reviewed.toInstant());
    }

    private static int num(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).intValue();
    }

    private static OffsetDateTime ts(Instant i) {
        return OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
    }
}
