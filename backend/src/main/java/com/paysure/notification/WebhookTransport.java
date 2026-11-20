package com.paysure.notification;

import java.net.URI;
import java.util.Map;

/** Port for the outbound HTTP call. The default implementation uses the JDK HTTP client; tests substitute a script. */
public interface WebhookTransport {

    Result send(URI url, Map<String, String> headers, String body);

    /** @param statusCode null if no HTTP response was received (connection error, timeout) */
    record Result(Integer statusCode, String error) {
        public boolean success() {
            return statusCode != null && statusCode >= 200 && statusCode < 300;
        }
    }
}
