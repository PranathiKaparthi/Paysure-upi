package com.paysure.notification.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Operator views of the event pipeline and of the sandbox receiver (ADMIN_DEMO only). */
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN_DEMO')")
@Tag(name = "Events (admin)")
public class AdminEventsController {

    private final JdbcTemplate jdbc;

    public AdminEventsController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/events/outbox")
    @Operation(summary = "Outbox status: counts by status and the most recent events")
    public Map<String, Object> outbox() {
        Map<String, Object> counts = jdbc.queryForMap("""
                select count(*) filter (where status = 'PENDING') as pending,
                       count(*) filter (where status = 'PUBLISHED') as published,
                       count(*) filter (where status = 'DEAD') as dead,
                       (select count(*) from inbox_events) as inbox
                from outbox_events""");
        List<Map<String, Object>> recent = jdbc.query("""
                select seq, id, event_type, topic, status, attempts, last_error, created_at, published_at
                from outbox_events order by seq desc limit 25""", (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("seq", rs.getLong("seq"));
            m.put("id", rs.getObject("id"));
            m.put("eventType", rs.getString("event_type"));
            m.put("topic", rs.getString("topic"));
            m.put("status", rs.getString("status"));
            m.put("attempts", rs.getInt("attempts"));
            m.put("lastError", rs.getString("last_error"));
            m.put("createdAt", rs.getObject("created_at", OffsetDateTime.class).toInstant());
            OffsetDateTime p = rs.getObject("published_at", OffsetDateTime.class);
            m.put("publishedAt", p == null ? null : p.toInstant());
            return m;
        });
        Map<String, Object> out = new LinkedHashMap<>(counts);
        out.put("recent", recent);
        return out;
    }

    @GetMapping("/webhooks/receipts")
    @Operation(summary = "What the built-in sandbox receiver saw: calls per event and whether it was processed (once)")
    public List<Map<String, Object>> receipts() {
        return jdbc.query("""
                select endpoint_id, event_id, event_type, calls, first_seen, last_seen, processed_at, last_result
                from webhook_receipts order by first_seen desc limit 50""", (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("endpointId", rs.getObject("endpoint_id"));
            m.put("eventId", rs.getObject("event_id"));
            m.put("eventType", rs.getString("event_type"));
            m.put("calls", rs.getInt("calls"));
            m.put("firstSeen", rs.getObject("first_seen", OffsetDateTime.class).toInstant());
            m.put("processedAt", rs.getObject("processed_at", OffsetDateTime.class) == null ? null
                    : rs.getObject("processed_at", OffsetDateTime.class).toInstant());
            m.put("lastResult", rs.getString("last_result"));
            return m;
        });
    }
}
