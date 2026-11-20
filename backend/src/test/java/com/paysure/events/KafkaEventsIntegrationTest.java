package com.paysure.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.paysure.notification.NotificationEventHandler;
import com.paysure.notification.WebhookEndpointService;
import com.paysure.support.ReliabilityTestBase;
import com.paysure.support.TestDatabase;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * End to end through a REAL Kafka broker (in-process KRaft): payment commits → outbox row → publisher → topic →
 * consumer → inbox + webhook delivery. Also: redelivery of the same event, and a poison record.
 */
/**
 * {@code @DirtiesContext}: this context runs a live outbox publisher and Kafka consumer against the shared test database.
 * It must be closed when the class ends, otherwise it would keep consuming other test classes' events in the background.
 */
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@EmbeddedKafka(partitions = 3, bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@TestPropertySource(properties = {
        "paysure.events.publisher=kafka",
        "paysure.events.publisher-enabled=true",
        "paysure.events.poll-millis=100",
        "paysure.events.consumers-enabled=true",
        "paysure.events.create-topics=true",
        "paysure.events.partitions=3"})
class KafkaEventsIntegrationTest extends ReliabilityTestBase {

    @Autowired
    WebhookEndpointService endpoints;
    @Autowired
    KafkaTemplate<String, String> kafka;
    @Autowired
    EmbeddedKafkaBroker broker;
    @Autowired
    MeterRegistry registry;

    /** Rows left by other test classes in the shared database must not flood this broker. */
    @DynamicPropertySource
    static void parkOldOutboxRows(DynamicPropertyRegistry ignored) {
        TestDatabase.Connection db = TestDatabase.get();
        try (var c = DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password());
             var st = c.createStatement()) {
            st.executeUpdate("update outbox_events set status = 'PUBLISHED', published_at = now() where status = 'PENDING'");
        } catch (java.sql.SQLException e) {
            // schema not created yet (this class ran first): nothing to park
        }
    }

    private UUID adminEndpoint(Party admin, String types) {
        return endpoints.create(admin.user().id(), "http://localhost:9/hook", List.of(types), "kafka-e2e").endpoint().id();
    }

    private long deliveries(UUID endpoint) {
        return jdbc.queryForObject("select count(*) from webhook_deliveries where endpoint_id = ?", Long.class, endpoint);
    }

    private Map<String, Object> completedOutboxRow(String paymentId) {
        return jdbc.queryForList("select * from outbox_events where aggregate_id = ?::uuid and event_type = 'payment.completed'", paymentId).get(0);
    }

    private Consumer<String, String> consumerOn(String topic) {
        Map<String, Object> props = KafkaTestUtils.consumerProps("test-" + UUID.randomUUID(), "true", broker);
        props.put("auto.offset.reset", "earliest");
        Consumer<String, String> c = new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new StringDeserializer()).createConsumer();
        c.subscribe(List.of(topic));
        return c;
    }

    @Test
    void aCommittedPaymentFlowsOutboxToKafkaToAnInboxRecordedWebhookDelivery() throws Exception {
        Party lab = adminParty();
        Party payee = party("k1p");
        UUID endpoint = adminEndpoint(lab, "payment.completed");

        String id = body(pay(lab, payee.vpa(), 5_000, key())).get("id").asText();
        Map<String, Object> row = completedOutboxRow(id);
        UUID eventId = (UUID) row.get("id");

        await().atMost(Duration.ofSeconds(40)).pollInterval(Duration.ofMillis(250)).untilAsserted(
                () -> assertThat(deliveries(endpoint)).isEqualTo(1));

        assertThat(jdbc.queryForObject("select event_id from webhook_deliveries where endpoint_id = ?", UUID.class, endpoint)).isEqualTo(eventId);
        assertThat(jdbc.queryForObject("select count(*) from inbox_events where consumer = ? and event_id = ?", Long.class,
                NotificationEventHandler.CONSUMER, eventId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from outbox_events where id = ?", String.class, eventId)).isEqualTo("PUBLISHED");
        assertThat(jdbc.queryForList("select status from outbox_events where aggregate_id = ?::uuid", String.class, id)).containsOnly("PUBLISHED");

        // The broker holds the event with the aggregate id as key and the event id in a header.
        try (Consumer<String, String> c = consumerOn("paysure.payment.completed")) {
            ConsumerRecord<String, String> found = null;
            long deadline = System.currentTimeMillis() + 20_000;
            while (found == null && System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, String> records = KafkaTestUtils.getRecords(c, Duration.ofSeconds(2));
                for (ConsumerRecord<String, String> r : records) {
                    if (id.equals(r.key())) {
                        found = r;
                    }
                }
            }
            assertThat(found).as("event present on the topic").isNotNull();
            assertThat(new String(found.headers().lastHeader("eventId").value(), StandardCharsets.UTF_8)).isEqualTo(eventId.toString());
            assertThat(found.value()).isEqualTo(row.get("payload"));
        }
    }

    @Test
    void case19_theSameEventRedeliveredThroughKafkaIsIgnoredByTheInbox() throws Exception {
        Party lab = adminParty();
        Party payee = party("k2p");
        UUID endpoint = adminEndpoint(lab, "payment.completed");
        String id = body(pay(lab, payee.vpa(), 5_000, key())).get("id").asText();
        Map<String, Object> row = completedOutboxRow(id);
        UUID eventId = (UUID) row.get("id");
        await().atMost(Duration.ofSeconds(40)).untilAsserted(() -> assertThat(deliveries(endpoint)).isEqualTo(1));

        double before = registry.counter("events.duplicates.ignored").count();
        // An at-least-once redelivery (or a producer retry that duplicated the record): same bytes, same key.
        kafka.send("paysure.payment.completed", id, (String) row.get("payload")).get();
        kafka.send("paysure.payment.completed", id, (String) row.get("payload")).get();

        await().atMost(Duration.ofSeconds(30)).untilAsserted(
                () -> assertThat(registry.counter("events.duplicates.ignored").count() - before).isGreaterThanOrEqualTo(2.0));
        assertThat(deliveries(endpoint)).as("no second webhook delivery").isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from inbox_events where event_id = ?", Long.class, eventId)).isEqualTo(1);
        // Kafka redelivery never touches money: the ledger is unchanged.
        assertThat(transferJournals(id)).isEqualTo(1);
        assertLedgerInvariants();
    }

    @Test
    void aPoisonRecordIsDeadLetteredAndTheConsumerKeepsProcessingLaterEvents() throws Exception {
        Party lab = adminParty();
        Party payee = party("k3p");
        UUID endpoint = adminEndpoint(lab, "payment.completed");
        String poison = "{ definitely not an event " + UUID.randomUUID();

        kafka.send("paysure.payment.completed", "poison-key", poison).get();

        try (Consumer<String, String> dlt = consumerOn("paysure.payment.completed.DLT")) {
            boolean seen = false;
            long deadline = System.currentTimeMillis() + 40_000;       // 3 retries, one second apart, then the DLT
            while (!seen && System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> r : KafkaTestUtils.getRecords(dlt, Duration.ofSeconds(2))) {
                    seen |= poison.equals(r.value());
                }
            }
            assertThat(seen).as("poison record reached the dead-letter topic").isTrue();
        }

        // The partition is not stuck: a healthy event published afterwards is still delivered.
        String id = body(pay(lab, payee.vpa(), 5_000, key())).get("id").asText();
        await().atMost(Duration.ofSeconds(40)).untilAsserted(() -> assertThat(deliveries(endpoint)).isEqualTo(1));
        assertThat(completedOutboxRow(id)).isNotNull();
    }
}
