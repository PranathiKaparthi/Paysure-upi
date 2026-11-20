package com.paysure.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.paysure.events.OutboxPublisher;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

/** Helpers for outbox / webhook tests. The publisher and transport are test doubles unless a test overrides them. */
public abstract class EventsTestBase extends ReliabilityTestBase {

    @Autowired
    protected OutboxPublisher outbox;
    @Autowired
    protected RecordingEventPublisher recorder;
    @Autowired
    protected RecordingWebhookTransport transport;

    /** Starts from an empty queue: earlier tests share the database and leave PENDING outbox rows behind. */
    @BeforeEach
    void drainOutbox() {
        recorder.healAll();
        transport.reset();
        for (int i = 0; i < 100 && outbox.pendingCount() > 0; i++) {
            outbox.publishPending();
        }
        assertThat(outbox.pendingCount()).isZero();
        recorder.clear();
        // Endpoints registered by earlier tests (demo admins receive EVERY event) would get deliveries for this test's
        // events; switch them off. Endpoints this test creates are enabled as usual.
        jdbc.update("update webhook_endpoints set enabled = false where enabled");
        // Deliveries queued by earlier tests would be claimed by this test's worker runs; park them as dead.
        jdbc.update("update webhook_deliveries set status = 'DEAD', lease_until = null, last_error = 'parked by test cleanup' "
                + "where status in ('PENDING', 'RETRYING', 'DELIVERING')");
    }

    @AfterEach
    void healEverything() {
        recorder.healAll();
        transport.reset();
    }

    /** Outbox rows of one aggregate in publication order: [{seq, id, event_type, status, attempts, payload}]. */
    protected List<Map<String, Object>> outboxRows(String aggregateId) {
        return jdbc.queryForList("select * from outbox_events where aggregate_id = ?::uuid order by seq", aggregateId);
    }

    protected List<String> outboxTypes(String aggregateId) {
        return outboxRows(aggregateId).stream().map(r -> (String) r.get("event_type")).toList();
    }

    protected JsonNode envelope(Map<String, Object> outboxRow) throws Exception {
        return readJson((String) outboxRow.get("payload"));
    }

    protected UUID eventId(Map<String, Object> outboxRow) {
        return (UUID) outboxRow.get("id");
    }
}
