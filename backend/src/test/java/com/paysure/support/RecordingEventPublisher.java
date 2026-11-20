package com.paysure.support;

import com.paysure.events.EventPublisher;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Test publisher: records what the outbox publisher hands over and can be switched into a failing state. */
@Component
@ConditionalOnProperty(prefix = "paysure.events", name = "publisher", havingValue = "recording")
public class RecordingEventPublisher implements EventPublisher {

    private final List<Message> published = Collections.synchronizedList(new ArrayList<>());
    private final AtomicBoolean failing = new AtomicBoolean(false);
    private final java.util.Set<java.util.UUID> poison = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @Override
    public void publish(Message message) throws PublishException {
        if (failing.get() || poison.contains(message.eventId())) {
            throw new PublishException("simulated broker outage", new IllegalStateException("broker down"));
        }
        published.add(message);
    }

    /** Fail only for this event id (a poison message), leaving the rest healthy. */
    public void failFor(java.util.UUID eventId) {
        poison.add(eventId);
    }

    public void healAll() {
        poison.clear();
        failing.set(false);
    }

    public void setFailing(boolean failing) {
        this.failing.set(failing);
    }

    public List<Message> published() {
        synchronized (published) {
            return List.copyOf(published);
        }
    }

    public void clear() {
        published.clear();
    }
}
