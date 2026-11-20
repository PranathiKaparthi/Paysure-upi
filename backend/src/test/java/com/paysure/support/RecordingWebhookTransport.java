package com.paysure.support;

import com.paysure.notification.WebhookTransport;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Test transport: records every request and answers from a script (then a default). Lets tests drive retries
 * deterministically without a network.
 */
@Component
@ConditionalOnProperty(prefix = "paysure.webhooks", name = "transport", havingValue = "recording")
public class RecordingWebhookTransport implements WebhookTransport {

    public record Call(URI url, Map<String, String> headers, String body) {
    }

    private final List<Call> calls = Collections.synchronizedList(new ArrayList<>());
    private final Queue<Result> script = new LinkedList<>();
    private volatile Result fallback = new Result(200, null);
    private volatile java.util.function.Function<Call, Result> handler;

    @Override
    public Result send(URI url, Map<String, String> headers, String body) {
        Call call = new Call(url, Map.copyOf(headers), body);
        calls.add(call);
        if (handler != null) {
            return handler.apply(call);
        }
        synchronized (script) {
            Result next = script.poll();
            return next != null ? next : fallback;
        }
    }

    /** The next calls get these results in order; afterwards {@link #respondWith} applies. */
    public void script(Result... results) {
        synchronized (script) {
            script.clear();
            script.addAll(List.of(results));
        }
    }

    /** Route requests to code under test (e.g. the sandbox receiver); overrides the script. */
    public void handleWith(java.util.function.Function<Call, Result> handler) {
        this.handler = handler;
    }

    public void respondWith(Result result) {
        this.fallback = result;
    }

    public List<Call> calls() {
        synchronized (calls) {
            return List.copyOf(calls);
        }
    }

    public List<Call> callsTo(String urlContains) {
        return calls().stream().filter(c -> c.url().toString().contains(urlContains)).toList();
    }

    public void reset() {
        calls.clear();
        synchronized (script) {
            script.clear();
        }
        fallback = new Result(200, null);
        handler = null;
    }
}
