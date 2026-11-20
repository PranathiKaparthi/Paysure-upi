package com.paysure.notification.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paysure.notification.WebhookEndpointService;
import com.paysure.notification.WebhookProperties;
import com.paysure.notification.WebhookSigner;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A built-in webhook RECEIVER for local demonstrations (reachable without a JWT: the signature is the credential).
 * It shows what a correct receiver does:
 * <ol>
 *   <li>verify the HMAC over the raw body and the timestamp window — otherwise 401, nothing recorded;</li>
 *   <li>deduplicate by event id — the "business processing" (setting {@code processed_at}) happens at most once per
 *       (endpoint, event), however many times PaySure retries;</li>
 *   <li>answer 2xx for a duplicate so the sender stops retrying.</li>
 * </ol>
 * {@code ?fail=N} makes the first N calls for each event fail with 500 to demonstrate retries.
 */
@RestController
@RequestMapping("/webhook-sandbox")
@ConditionalOnProperty(prefix = "paysure.webhooks", name = "sandbox-receiver-enabled", havingValue = "true")
public class SandboxReceiverController {

    private static final int MAX_BODY = 64 * 1024;

    private final JdbcTemplate jdbc;
    private final WebhookEndpointService endpoints;
    private final WebhookProperties props;
    private final ObjectMapper mapper;
    private final Clock clock;

    public SandboxReceiverController(JdbcTemplate jdbc, WebhookEndpointService endpoints, WebhookProperties props,
                                     ObjectMapper mapper, Clock clock) {
        this.jdbc = jdbc;
        this.endpoints = endpoints;
        this.props = props;
        this.mapper = mapper;
        this.clock = clock;
    }

    @PostMapping("/receiver")
    public ResponseEntity<Map<String, Object>> receive(
            @RequestBody String body,
            @RequestParam(defaultValue = "0") int fail,
            @RequestHeader(value = WebhookSigner.ENDPOINT_ID_HEADER, required = false) String endpointHeader,
            @RequestHeader(value = WebhookSigner.EVENT_ID_HEADER, required = false) String eventHeader,
            @RequestHeader(value = WebhookSigner.EVENT_TYPE_HEADER, required = false) String typeHeader,
            @RequestHeader(value = WebhookSigner.TIMESTAMP_HEADER, required = false) String timestamp,
            @RequestHeader(value = WebhookSigner.SIGNATURE_HEADER, required = false) String signature) {
        if (body.length() > MAX_BODY) {
            return reply(HttpStatus.PAYLOAD_TOO_LARGE, "body too large");
        }
        UUID endpointId;
        UUID eventId;
        try {
            endpointId = UUID.fromString(endpointHeader);
            eventId = UUID.fromString(eventHeader);
        } catch (RuntimeException e) {
            return reply(HttpStatus.UNAUTHORIZED, "missing or invalid identification headers");
        }
        var versions = jdbc.queryForList("select secret_version from webhook_endpoints where id = ?", Integer.class, endpointId);
        if (versions.isEmpty()) {
            return reply(HttpStatus.UNAUTHORIZED, "unknown endpoint"); // same answer as a bad signature
        }
        String secret = endpoints.secretFor(endpointId, versions.get(0));
        WebhookSigner.Result verdict = WebhookSigner.verify(secret, timestamp, signature, body, props.replayTolerance(), clock.instant());
        if (verdict != WebhookSigner.Result.VALID) {
            return reply(HttpStatus.UNAUTHORIZED, "signature rejected: " + verdict);
        }
        try {
            JsonNode json = mapper.readTree(body);
            if (!eventId.toString().equals(json.path("eventId").asText())) {
                return reply(HttpStatus.BAD_REQUEST, "event id header does not match the signed body");
            }
        } catch (Exception e) {
            return reply(HttpStatus.BAD_REQUEST, "body is not JSON");
        }

        Map<String, Object> row = jdbc.queryForMap("""
                insert into webhook_receipts (endpoint_id, event_id, event_type, last_result)
                values (?, ?, ?, 'RECEIVED')
                on conflict (endpoint_id, event_id) do update set calls = webhook_receipts.calls + 1, last_seen = now()
                returning calls, processed_at""", endpointId, eventId, typeHeader == null ? "unknown" : typeHeader);
        int calls = ((Number) row.get("calls")).intValue();
        if (calls <= Math.max(0, Math.min(fail, 10))) {
            jdbc.update("update webhook_receipts set last_result = 'FAILED_ON_PURPOSE' where endpoint_id = ? and event_id = ?", endpointId, eventId);
            return reply(HttpStatus.INTERNAL_SERVER_ERROR, "simulated receiver failure " + calls + " of " + fail);
        }
        int processed = jdbc.update("""
                update webhook_receipts set processed_at = now(), last_result = 'PROCESSED'
                where endpoint_id = ? and event_id = ? and processed_at is null""", endpointId, eventId);
        if (processed == 1) {
            return reply(HttpStatus.OK, "processed");
        }
        jdbc.update("update webhook_receipts set last_result = 'DUPLICATE_IGNORED' where endpoint_id = ? and event_id = ?", endpointId, eventId);
        return reply(HttpStatus.OK, "duplicate ignored");
    }

    private static ResponseEntity<Map<String, Object>> reply(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("status", message));
    }
}
