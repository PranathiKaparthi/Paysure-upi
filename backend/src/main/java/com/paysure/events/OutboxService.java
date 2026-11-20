package com.paysure.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes domain events to the outbox. It REQUIRES an active transaction ({@code MANDATORY}) and uses the same
 * JDBC connection as the caller, so the event row commits or rolls back together with the business change.
 * It never talks to Kafka.
 */
@Service
public class OutboxService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;

    public OutboxService(JdbcTemplate jdbc, ObjectMapper mapper, Clock clock) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** The message published to Kafka and, unchanged, delivered to webhook receivers. */
    public record Envelope(UUID eventId, String eventType, Instant occurredAt, String aggregateType, UUID aggregateId,
                           Map<String, Object> data) {
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID record(String eventType, String aggregateType, UUID aggregateId, Map<String, Object> data) {
        UUID eventId = UUID.randomUUID();
        Envelope envelope = new Envelope(eventId, eventType, clock.instant(), aggregateType, aggregateId, new LinkedHashMap<>(data));
        String payload;
        try {
            payload = mapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialise event " + eventType, e);
        }
        jdbc.update("""
                insert into outbox_events (id, aggregate_type, aggregate_id, event_type, topic, payload)
                values (?, ?, ?, ?, ?, ?)""", eventId, aggregateType, aggregateId, eventType, EventTypes.topicFor(eventType), payload);
        return eventId;
    }
}
