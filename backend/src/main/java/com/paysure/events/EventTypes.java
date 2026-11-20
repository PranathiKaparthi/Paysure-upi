package com.paysure.events;

import java.util.List;
import java.util.Map;

/** Event type names and their Kafka topics. The message key is always the aggregate id (per-aggregate ordering). */
public final class EventTypes {

    public static final String PAYMENT_CREATED = "payment.created";
    public static final String PAYMENT_COMPLETED = "payment.completed";
    public static final String PAYMENT_FAILED = "payment.failed";
    public static final String RECONCILIATION_COMPLETED = "reconciliation.completed";
    /** Produced by the risk engine (Phase 5). The topic and webhook plumbing already exist. */
    public static final String RISK_ALERT = "risk.alert";

    public static final Map<String, String> TOPICS = Map.of(
            PAYMENT_CREATED, "paysure.payment.created",
            PAYMENT_COMPLETED, "paysure.payment.completed",
            PAYMENT_FAILED, "paysure.payment.failed",
            RECONCILIATION_COMPLETED, "paysure.reconciliation.completed",
            RISK_ALERT, "paysure.risk.alert");

    public static final List<String> ALL = List.of(PAYMENT_CREATED, PAYMENT_COMPLETED, PAYMENT_FAILED,
            RECONCILIATION_COMPLETED, RISK_ALERT);

    private EventTypes() {
    }

    public static String topicFor(String eventType) {
        String topic = TOPICS.get(eventType);
        if (topic == null) {
            throw new IllegalArgumentException("unknown event type " + eventType);
        }
        return topic;
    }

    public static String[] allTopics() {
        return ALL.stream().map(TOPICS::get).toArray(String[]::new);
    }
}
