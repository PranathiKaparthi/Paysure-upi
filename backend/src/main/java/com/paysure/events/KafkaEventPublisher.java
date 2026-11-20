package com.paysure.events;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/** Publishes to Kafka and waits for the broker's acknowledgement (producer: acks=all, idempotence on). */
@Component
@ConditionalOnProperty(prefix = "paysure.events", name = "publisher", havingValue = "kafka", matchIfMissing = true)
public class KafkaEventPublisher implements EventPublisher {

    private final KafkaTemplate<String, String> template;
    private final EventsProperties props;

    public KafkaEventPublisher(KafkaTemplate<String, String> template, EventsProperties props) {
        this.template = template;
        this.props = props;
    }

    @Override
    public void publish(Message m) throws PublishException {
        ProducerRecord<String, String> record = new ProducerRecord<>(m.topic(), m.key(), m.payload());
        record.headers().add("eventId", m.eventId().toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventType", m.eventType().getBytes(StandardCharsets.UTF_8));
        try {
            template.send(record).get(props.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PublishException("interrupted while publishing " + m.eventId(), e);
        } catch (ExecutionException | TimeoutException e) {
            throw new PublishException("Kafka publish failed for " + m.eventId() + ": " + e.getMessage(), e);
        }
    }
}
