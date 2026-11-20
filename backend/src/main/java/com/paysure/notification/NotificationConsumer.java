package com.paysure.notification;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Kafka entry point. A thrown exception makes the container retry the record (3 × 1 s) and then dead-letter it to
 * {@code <topic>.DLT}; the offset is committed after each record (AckMode.RECORD), i.e. after the handler's database
 * transaction has committed.
 */
@Component
public class NotificationConsumer {

    private static final Logger log = LoggerFactory.getLogger(NotificationConsumer.class);

    private final NotificationEventHandler handler;

    public NotificationConsumer(NotificationEventHandler handler) {
        this.handler = handler;
    }

    @KafkaListener(id = "webhook-notifications", groupId = "paysure-webhook-notifications",
            topics = "#{T(com.paysure.events.EventTypes).allTopics()}",
            autoStartup = "${paysure.events.consumers-enabled:true}")
    public void onEvent(ConsumerRecord<String, String> record) {
        NotificationEventHandler.Outcome o = handler.handle(record.value());
        log.debug("Consumed {}-{}@{}: duplicate={} deliveries={}", record.topic(), record.partition(), record.offset(),
                o.duplicate(), o.deliveriesCreated());
    }
}
