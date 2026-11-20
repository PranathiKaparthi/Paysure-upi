package com.paysure.events;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param publisher         {@code kafka} (default) or {@code recording} (tests): which {@link EventPublisher} is active
 * @param publisherEnabled  run the scheduled outbox publisher
 * @param pollMillis        delay between publisher polls
 * @param batchSize         outbox rows published per poll
 * @param maxAttempts       an outbox row that fails this many times is marked DEAD so it cannot block later events
 * @param sendTimeout       how long to wait for a broker acknowledgement
 * @param maxBackoff        upper bound of the publisher's pause after a failure
 * @param consumersEnabled  start the Kafka consumers
 * @param createTopics      create topics at startup (needed when the broker has auto-creation disabled)
 * @param partitions        partitions per topic (and per dead-letter topic)
 * @param replicas          replication factor
 */
@ConfigurationProperties("paysure.events")
public record EventsProperties(
        @DefaultValue("kafka") String publisher,
        @DefaultValue("true") boolean publisherEnabled,
        @DefaultValue("500") long pollMillis,
        @DefaultValue("100") int batchSize,
        @DefaultValue("20") int maxAttempts,
        @DefaultValue("5s") Duration sendTimeout,
        @DefaultValue("30s") Duration maxBackoff,
        @DefaultValue("true") boolean consumersEnabled,
        @DefaultValue("true") boolean createTopics,
        @DefaultValue("3") int partitions,
        @DefaultValue("1") short replicas) {
}
