package com.paysure.events;

import java.util.UUID;

/**
 * Port to the event bus. The Kafka implementation is the default; tests substitute a recording/failing one.
 * Implementations must block until the broker has acknowledged the message, or throw {@link PublishException}.
 * Delivery is at-least-once: the outbox publisher may call {@code publish} again for a message whose
 * acknowledgement it did not see, so consumers must deduplicate by {@code eventId}.
 */
public interface EventPublisher {

    void publish(Message message) throws PublishException;

    record Message(UUID eventId, String eventType, String topic, String key, String payload) {
    }

    class PublishException extends Exception {
        public PublishException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
