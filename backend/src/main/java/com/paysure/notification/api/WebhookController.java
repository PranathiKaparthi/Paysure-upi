package com.paysure.notification.api;

import com.paysure.events.EventTypes;
import com.paysure.identity.application.Caller;
import com.paysure.notification.WebhookDeliveryService;
import com.paysure.notification.WebhookDeliveryService.Delivery;
import com.paysure.notification.WebhookDeliveryService.Detail;
import com.paysure.notification.WebhookEndpointService;
import com.paysure.notification.WebhookEndpointService.Created;
import com.paysure.notification.WebhookEndpointService.Endpoint;
import com.paysure.notification.WebhookProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/webhooks")
@Tag(name = "Webhooks")
public class WebhookController {

    private final WebhookEndpointService endpoints;
    private final WebhookDeliveryService deliveries;
    private final WebhookProperties props;

    public WebhookController(WebhookEndpointService endpoints, WebhookDeliveryService deliveries, WebhookProperties props) {
        this.endpoints = endpoints;
        this.deliveries = deliveries;
        this.props = props;
    }

    public record CreateEndpointRequest(@NotBlank @Size(max = 500) String url,
                                        @Size(max = 10) List<@Size(max = 50) String> eventTypes,
                                        @Size(max = 100) String description) {
    }

    public record EndpointResponse(UUID id, String url, List<String> eventTypes, String description, boolean enabled,
                                   int secretVersion, java.time.Instant createdAt) {
        static EndpointResponse of(Endpoint e) {
            return new EndpointResponse(e.id(), e.url(), e.eventTypes(), e.description(), e.enabled(), e.secretVersion(), e.createdAt());
        }
    }

    /** The signing secret appears ONLY here (create / rotate). It is derived on demand and never stored. */
    public record CreatedEndpointResponse(EndpointResponse endpoint, String secret) {
        static CreatedEndpointResponse of(Created c) {
            return new CreatedEndpointResponse(EndpointResponse.of(c.endpoint()), c.secret());
        }
    }

    @GetMapping("/config")
    @Operation(summary = "Signature scheme, known event types and the sandbox receiver URL")
    public Map<String, Object> config() {
        return Map.of(
                "eventTypes", EventTypes.ALL,
                "sandboxReceiverEnabled", props.sandboxReceiverEnabled(),
                "sandboxReceiverUrl", props.sandboxReceiverUrl(),
                "signature", "X-PaySure-Signature: v1=hex(HMAC-SHA256(secret, timestamp + \".\" + rawBody)); timestamp in X-PaySure-Timestamp",
                "replayToleranceSeconds", props.replayTolerance().toSeconds(),
                "maxAttempts", props.maxAttempts());
    }

    @PostMapping("/endpoints")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Register a webhook endpoint (URL is checked against SSRF rules). The secret is returned once.")
    public CreatedEndpointResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateEndpointRequest req) {
        return CreatedEndpointResponse.of(endpoints.create(Caller.from(jwt).userId(), req.url(), req.eventTypes(), req.description()));
    }

    @GetMapping("/endpoints")
    public List<EndpointResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return endpoints.list(Caller.from(jwt).userId()).stream().map(EndpointResponse::of).toList();
    }

    @PostMapping("/endpoints/{id}/enable")
    public EndpointResponse enable(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return EndpointResponse.of(endpoints.setEnabled(Caller.from(jwt).userId(), id, true));
    }

    @PostMapping("/endpoints/{id}/disable")
    public EndpointResponse disable(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return EndpointResponse.of(endpoints.setEnabled(Caller.from(jwt).userId(), id, false));
    }

    @PostMapping("/endpoints/{id}/rotate-secret")
    @Operation(summary = "Issue a new signing secret (the previous one stops working immediately)")
    public CreatedEndpointResponse rotate(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return CreatedEndpointResponse.of(endpoints.rotateSecret(Caller.from(jwt).userId(), id));
    }

    @GetMapping("/deliveries")
    @Operation(summary = "Delivery log: status, attempts, last HTTP status/error")
    public List<Delivery> deliveries(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) UUID endpointId,
                                     @RequestParam(required = false) String status, @RequestParam(defaultValue = "50") int limit) {
        return deliveries.list(Caller.from(jwt).userId(), endpointId, status, limit);
    }

    @GetMapping("/deliveries/{id}")
    public Detail delivery(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return deliveries.detail(Caller.from(jwt).userId(), id);
    }

    @PostMapping("/deliveries/{id}/retry")
    @Operation(summary = "Re-queue a dead-lettered delivery (same event id, so processed-once receivers ignore a repeat)")
    public Delivery retry(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return deliveries.retry(Caller.from(jwt).userId(), id);
    }
}
