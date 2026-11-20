package com.paysure.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Idempotent consumer of domain events. In ONE database transaction it
 * <ol>
 *   <li>records {@code (consumer, eventId)} in {@code inbox_events} — the primary key rejects a second delivery, and</li>
 *   <li>creates a durable {@code webhook_deliveries} row per matching endpoint ({@code UNIQUE(endpoint, event)}).</li>
 * </ol>
 * If the transaction fails nothing is recorded and Kafka redelivers; if the record is delivered twice the second call
 * finds the inbox row and does nothing. The Kafka offset is committed only after this method returns (AckMode.RECORD).
 * This yields effectively-once <em>notification creation</em> — it is not, and does not claim to be, exactly-once money
 * movement: money moves only inside PostgreSQL transactions that never depend on Kafka.
 */
@Service
public class NotificationEventHandler {

    public static final String CONSUMER = "webhook-notifications";
    private static final Logger log = LoggerFactory.getLogger(NotificationEventHandler.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final WebhookProperties props;
    private final Clock clock;
    private final Counter duplicates;
    private final Counter created;

    public NotificationEventHandler(JdbcTemplate jdbc, ObjectMapper mapper, WebhookProperties props, Clock clock,
                                    MeterRegistry registry) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.props = props;
        this.clock = clock;
        this.duplicates = Counter.builder("events.duplicates.ignored").description("Redelivered events ignored by the inbox").register(registry);
        this.created = Counter.builder("webhook.deliveries.enqueued").description("Webhook deliveries queued from events").register(registry);
    }

    public record Outcome(boolean duplicate, int deliveriesCreated) {
    }

    @Transactional
    public Outcome handle(String json) {
        JsonNode event;
        try {
            event = mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("event is not valid JSON", e);
        }
        UUID eventId = uuid(event, "eventId");
        String type = text(event, "eventType");
        UUID aggregateId = uuid(event, "aggregateId");

        int inserted = jdbc.update("insert into inbox_events (consumer, event_id, event_type) values (?, ?, ?) on conflict do nothing",
                CONSUMER, eventId, type);
        if (inserted == 0) {
            duplicates.increment();
            log.info("Event {} ({}) already processed by {}; ignoring redelivery", eventId, type, CONSUMER);
            return new Outcome(true, 0);
        }

        int n = 0;
        OffsetDateTime due = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        for (UUID endpointId : matchingEndpoints(type, aggregateId, event.path("data"))) {
            n += jdbc.update("""
                    insert into webhook_deliveries (id, endpoint_id, event_id, event_type, payload, max_attempts, next_attempt_at)
                    values (?, ?, ?, ?, ?, ?, ?) on conflict (endpoint_id, event_id) do nothing""",
                    UUID.randomUUID(), endpointId, eventId, type, json, props.maxAttempts(), due);
        }
        created.increment(n);
        return new Outcome(false, n);
    }

    /**
     * Audience: payment events go to the payer's and payee's endpoints; events carrying {@code data.userId} (risk
     * alerts) go to that user; everything else is operational. Demo admins also receive every event.
     */
    private List<UUID> matchingEndpoints(String type, UUID aggregateId, JsonNode data) {
        String typeMatch = "(e.event_types = '*' or (',' || e.event_types || ',') like '%,' || ? || ',%')";
        if (type.startsWith("payment.")) {
            return jdbc.query("""
                    select e.id from webhook_endpoints e join users u on u.id = e.owner_user_id
                    where e.enabled and""" + " " + typeMatch + """
                     and (u.role = 'ADMIN_DEMO'
                          or e.owner_user_id in (select payer_user_id from payments where id = ?)
                          or e.owner_user_id in (select payee_user_id from payments where id = ?))""",
                    (rs, i) -> rs.getObject(1, UUID.class), type, aggregateId, aggregateId);
        }
        if (data.hasNonNull("userId")) {
            UUID userId = UUID.fromString(data.get("userId").asText());
            return jdbc.query("""
                    select e.id from webhook_endpoints e join users u on u.id = e.owner_user_id
                    where e.enabled and""" + " " + typeMatch + " and (u.role = 'ADMIN_DEMO' or e.owner_user_id = ?)",
                    (rs, i) -> rs.getObject(1, UUID.class), type, userId);
        }
        return jdbc.query("""
                select e.id from webhook_endpoints e join users u on u.id = e.owner_user_id
                where e.enabled and""" + " " + typeMatch + " and u.role = 'ADMIN_DEMO'",
                (rs, i) -> rs.getObject(1, UUID.class), type);
    }

    private static UUID uuid(JsonNode n, String field) {
        try {
            return UUID.fromString(text(n, field));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("event field " + field + " is not a UUID", e);
        }
    }

    private static String text(JsonNode n, String field) {
        if (!n.hasNonNull(field)) {
            throw new IllegalArgumentException("event is missing " + field);
        }
        return n.get(field).asText();
    }
}
