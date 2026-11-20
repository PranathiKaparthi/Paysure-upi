package com.paysure.notification;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** JDK HTTP client transport. Redirects are NEVER followed (a redirect could point at an internal address). */
@Component
@ConditionalOnProperty(prefix = "paysure.webhooks", name = "transport", havingValue = "http", matchIfMissing = true)
public class HttpWebhookTransport implements WebhookTransport {

    private final HttpClient client;
    private final WebhookProperties props;

    public HttpWebhookTransport(WebhookProperties props) {
        this.props = props;
        this.client = HttpClient.newBuilder()
                .connectTimeout(props.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public Result send(URI url, Map<String, String> headers, String body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(url)
                .timeout(props.requestTimeout())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        headers.forEach(request::header);
        try {
            HttpResponse<Void> response = client.send(request.build(), HttpResponse.BodyHandlers.discarding());
            return new Result(response.statusCode(), null);
        } catch (IOException e) {
            return new Result(null, e.getClass().getSimpleName() + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(null, "interrupted");
        }
    }
}
