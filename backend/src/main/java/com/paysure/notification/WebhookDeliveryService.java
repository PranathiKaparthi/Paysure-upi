package com.paysure.notification;

import com.paysure.common.error.BusinessRuleException;
import com.paysure.common.error.NotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Read side of webhook deliveries (scoped to the caller's own endpoints) and manual retry of dead-lettered ones. */
@Service
public class WebhookDeliveryService {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public WebhookDeliveryService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public record Delivery(UUID id, UUID endpointId, String endpointUrl, UUID eventId, String eventType, String status,
                           int attempts, int maxAttempts, Instant nextAttemptAt, Integer lastStatusCode, String lastError,
                           Instant lastAttemptAt, Instant deliveredAt, Instant createdAt) {
    }

    public record Attempt(int attemptNo, Instant startedAt, long durationMs, Integer statusCode, String error, String outcome) {
    }

    public record Detail(Delivery delivery, String payload, List<Attempt> attempts) {
    }

    public List<Delivery> list(UUID ownerId, UUID endpointId, String status, int limit) {
        StringBuilder sql = new StringBuilder("""
                select d.*, e.url from webhook_deliveries d join webhook_endpoints e on e.id = d.endpoint_id
                where e.owner_user_id = ?""");
        List<Object> args = new ArrayList<>(List.of(ownerId));
        if (endpointId != null) {
            sql.append(" and d.endpoint_id = ?");
            args.add(endpointId);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" and d.status = ?");
            args.add(status.toUpperCase());
        }
        sql.append(" order by d.created_at desc, d.id limit ?");
        args.add(Math.max(1, Math.min(limit, 200)));
        return jdbc.query(sql.toString(), (rs, i) -> map(rs), args.toArray());
    }

    public Detail detail(UUID ownerId, UUID deliveryId) {
        Delivery d = jdbc.query("""
                select d.*, e.url from webhook_deliveries d join webhook_endpoints e on e.id = d.endpoint_id
                where d.id = ? and e.owner_user_id = ?""", (rs, i) -> map(rs), deliveryId, ownerId)
                .stream().findFirst().orElseThrow(() -> new NotFoundException("WEBHOOK_DELIVERY_NOT_FOUND", "Delivery not found."));
        String payload = jdbc.queryForObject("select payload from webhook_deliveries where id = ?", String.class, deliveryId);
        List<Attempt> attempts = jdbc.query("""
                select attempt_no, started_at, duration_ms, status_code, error, outcome from webhook_delivery_attempts
                where delivery_id = ? order by attempt_no, id""", (rs, i) -> new Attempt(rs.getInt("attempt_no"),
                rs.getObject("started_at", OffsetDateTime.class).toInstant(), rs.getLong("duration_ms"),
                (Integer) rs.getObject("status_code"), rs.getString("error"), rs.getString("outcome")), deliveryId);
        return new Detail(d, payload, attempts);
    }

    /** Re-queues a DEAD delivery. The event id is unchanged, so a receiver that already processed it ignores it. */
    public Delivery retry(UUID ownerId, UUID deliveryId) {
        Delivery d = detail(ownerId, deliveryId).delivery();
        if (!"DEAD".equals(d.status())) {
            throw new BusinessRuleException("DELIVERY_NOT_DEAD", "Only dead-lettered deliveries can be retried manually.");
        }
        jdbc.update("""
                update webhook_deliveries set status = 'PENDING', attempts = 0, next_attempt_at = ?, lease_until = null, last_error = null
                where id = ? and status = 'DEAD'""", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC), deliveryId);
        return detail(ownerId, deliveryId).delivery();
    }

    private static Delivery map(java.sql.ResultSet rs) throws java.sql.SQLException {
        OffsetDateTime delivered = rs.getObject("delivered_at", OffsetDateTime.class);
        OffsetDateTime last = rs.getObject("last_attempt_at", OffsetDateTime.class);
        return new Delivery(rs.getObject("id", UUID.class), rs.getObject("endpoint_id", UUID.class), rs.getString("url"),
                rs.getObject("event_id", UUID.class), rs.getString("event_type"), rs.getString("status"), rs.getInt("attempts"),
                rs.getInt("max_attempts"), rs.getObject("next_attempt_at", OffsetDateTime.class).toInstant(),
                (Integer) rs.getObject("last_status_code"), rs.getString("last_error"), last == null ? null : last.toInstant(),
                delivered == null ? null : delivered.toInstant(), rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }
}
