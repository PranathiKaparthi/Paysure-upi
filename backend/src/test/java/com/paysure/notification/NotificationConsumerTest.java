package com.paysure.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.paysure.events.EventTypes;
import com.paysure.notification.NotificationEventHandler.Outcome;
import com.paysure.support.EventsTestBase;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/** Mandatory case 19: duplicate (redelivered) Kafka events. The handler is what the Kafka listener calls. */
class NotificationConsumerTest extends EventsTestBase {

    @Autowired
    NotificationEventHandler handler;
    @Autowired
    WebhookEndpointService endpoints;
    @Autowired
    TransactionTemplate tx;

    private static final String URL = "http://localhost:9/hook";

    private UUID endpoint(UUID owner, List<String> types) {
        return endpoints.create(owner, URL, types, "test").endpoint().id();
    }

    private long deliveries(UUID endpointId) {
        return jdbc.queryForObject("select count(*) from webhook_deliveries where endpoint_id = ?", Long.class, endpointId);
    }

    private String completedPayload(String paymentId) {
        return outboxRows(paymentId).stream().filter(r -> "payment.completed".equals(r.get("event_type")))
                .map(r -> (String) r.get("payload")).findFirst().orElseThrow();
    }

    @Test
    void case19_aRedeliveredEventCreatesNoSecondDeliveryAndTheInboxRecordsItOnce() throws Exception {
        Party payer = party("nc1");
        Party payee = party("nc1p");
        UUID ep = endpoint(payer.user().id(), List.of("payment.completed"));
        String id = body(pay(payer, payee.vpa(), 5_000, key())).get("id").asText();
        String payload = completedPayload(id);
        UUID eventId = UUID.fromString(readJson(payload).get("eventId").asText());

        Outcome first = handler.handle(payload);
        Outcome again = handler.handle(payload);
        Outcome thrice = handler.handle(payload);

        assertThat(first.duplicate()).isFalse();
        assertThat(first.deliveriesCreated()).as("at least this user's endpoint; demo-admin endpoints from other tests also match").isGreaterThanOrEqualTo(1);
        assertThat(again.duplicate()).isTrue();
        assertThat(thrice.duplicate()).isTrue();
        assertThat(deliveries(ep)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from inbox_events where consumer = ? and event_id = ?", Long.class,
                NotificationEventHandler.CONSUMER, eventId)).isEqualTo(1);
        // The stored delivery body is the exact outbox payload (signature will cover the original bytes).
        assertThat(jdbc.queryForObject("select payload from webhook_deliveries where endpoint_id = ?", String.class, ep)).isEqualTo(payload);
    }

    @Test
    void simultaneousRedeliveryOfTheSameEventStillCreatesExactlyOneDelivery() throws Exception {
        Party payer = party("nc2");
        Party payee = party("nc2p");
        UUID ep = endpoint(payer.user().id(), List.of("*"));
        String id = body(pay(payer, payee.vpa(), 5_000, key())).get("id").asText();
        String payload = completedPayload(id);

        List<Outcome> outcomes = runConcurrently(8, () -> handler.handle(payload));

        assertThat(outcomes).filteredOn(o -> !o.duplicate()).hasSize(1);
        assertThat(deliveries(ep)).isEqualTo(1);
    }

    @Test
    void aFailedTransactionRecordsNothingSoTheRedeliveryIsProcessedNormally() throws Exception {
        Party payer = party("nc3");
        Party payee = party("nc3p");
        UUID ep = endpoint(payer.user().id(), List.of("payment.completed"));
        String id = body(pay(payer, payee.vpa(), 5_000, key())).get("id").asText();
        String payload = completedPayload(id);

        // The consumer's database transaction fails after the handler ran: inbox row and deliveries roll back together.
        tx.executeWithoutResult(s -> {
            handler.handle(payload);
            s.setRollbackOnly();
        });
        assertThat(deliveries(ep)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from inbox_events where event_id = ?::uuid", Long.class,
                readJsonQuiet(payload, "eventId"))).isZero();

        // Kafka redelivers (offset was never committed): now it is processed.
        assertThat(handler.handle(payload).deliveriesCreated()).isGreaterThanOrEqualTo(1);
        assertThat(deliveries(ep)).isEqualTo(1);
    }

    @Test
    void eventsGoOnlyToTheirAudienceAndMatchingTypesAndEnabledEndpoints() throws Exception {
        Party payer = party("nc4");
        Party payee = party("nc4p");
        Party stranger = party("nc4s");
        Party admin = adminParty();
        UUID payerEp = endpoint(payer.user().id(), List.of("*"));
        UUID payeeEp = endpoint(payee.user().id(), List.of("payment.completed"));
        UUID createdOnly = endpoint(payee.user().id(), List.of("payment.created"));
        UUID strangerEp = endpoint(stranger.user().id(), List.of("*"));
        UUID adminEp = endpoint(admin.user().id(), List.of("*"));
        UUID disabled = endpoint(payer.user().id(), List.of("*"));
        endpoints.setEnabled(payer.user().id(), disabled, false);

        String id = body(pay(payer, payee.vpa(), 5_000, key())).get("id").asText();
        for (Map<String, Object> row : outboxRows(id)) {
            handler.handle((String) row.get("payload"));
        }

        assertThat(deliveries(payerEp)).as("payer: created + completed").isEqualTo(2);
        assertThat(deliveries(payeeEp)).as("payee: only completed").isEqualTo(1);
        assertThat(deliveries(createdOnly)).isEqualTo(1);
        assertThat(deliveries(strangerEp)).as("unrelated user gets nothing").isZero();
        assertThat(deliveries(adminEp)).as("demo admins see every payment event").isEqualTo(2);
        assertThat(deliveries(disabled)).isZero();
    }

    @Test
    void operationalEventsGoToDemoAdminsOnly() throws Exception {
        Party user = party("nc5");
        Party admin = adminParty();
        UUID userEp = endpoint(user.user().id(), List.of("*"));
        UUID adminEp = endpoint(admin.user().id(), List.of("*"));
        String json = """
                {"eventId":"%s","eventType":"%s","occurredAt":"2026-01-01T00:00:00Z","aggregateType":"RECONCILIATION_RUN",
                 "aggregateId":"%s","data":{"checked":1}}""".formatted(UUID.randomUUID(), EventTypes.RECONCILIATION_COMPLETED, UUID.randomUUID());

        assertThat(handler.handle(json).deliveriesCreated()).isGreaterThanOrEqualTo(1);
        assertThat(deliveries(adminEp)).isEqualTo(1);
        assertThat(deliveries(userEp)).isZero();
    }

    @Test
    void riskAlertsGoToTheAffectedUserAndAdmins() throws Exception {
        Party user = party("nc6");
        Party other = party("nc6o");
        UUID userEp = endpoint(user.user().id(), List.of("risk.alert"));
        UUID otherEp = endpoint(other.user().id(), List.of("risk.alert"));
        String json = """
                {"eventId":"%s","eventType":"risk.alert","occurredAt":"2026-01-01T00:00:00Z","aggregateType":"RISK_ALERT",
                 "aggregateId":"%s","data":{"userId":"%s","score":75}}""".formatted(UUID.randomUUID(), UUID.randomUUID(), user.user().id());
        handler.handle(json);
        assertThat(deliveries(userEp)).isEqualTo(1);
        assertThat(deliveries(otherEp)).isZero();
    }

    @Test
    void malformedEventsAreRejectedSoTheContainerCanRetryAndDeadLetterThem() {
        assertThatThrownBy(() -> handler.handle("this is not json")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> handler.handle("{\"eventType\":\"payment.created\"}")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventId");
        assertThatThrownBy(() -> handler.handle("{\"eventId\":\"nope\",\"eventType\":\"x\",\"aggregateId\":\"y\"}"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private String readJsonQuiet(String json, String field) {
        try {
            return readJson(json).get(field).asText();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
