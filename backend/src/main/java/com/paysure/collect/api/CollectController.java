package com.paysure.collect.api;

import com.paysure.collect.application.CollectService;
import com.paysure.collect.domain.CollectRequest;
import com.paysure.collect.domain.CollectStatus;
import com.paysure.identity.application.Caller;
import com.paysure.payment.api.PaymentDtos.PaymentResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
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
@RequestMapping("/api/collect-requests")
@Tag(name = "Collect requests (request money)")
public class CollectController {

    private final CollectService collects;

    public CollectController(CollectService collects) {
        this.collects = collects;
    }

    public record CreateCollectRequest(
            @NotBlank @Size(max = 64) String requesterVpa,
            @NotBlank @Size(max = 64) String payerVpa,
            @NotNull @Positive @Max(10_000_000L) Long amountPaise,
            @Size(max = 100) String note,
            @Min(1) @Max(10_080) Integer expiresInMinutes) {
    }

    public enum Role { REQUESTER, PAYER }

    public record CollectResponse(UUID id, CollectStatus status, Role myRole, String requesterVpa, String payerVpa,
                                  long amountPaise, String note, UUID paymentId, Instant expiresAt, Instant decidedAt,
                                  Instant createdAt) {
        static CollectResponse of(CollectRequest r, UUID viewer, Instant now) {
            return new CollectResponse(r.getId(), r.effectiveStatus(now),
                    r.getPayerUserId().equals(viewer) ? Role.PAYER : Role.REQUESTER, r.getRequesterVpa(), r.getPayerVpa(),
                    r.getAmountPaise(), r.getNote(), r.getPaymentId(), r.getExpiresAt(), r.getDecidedAt(), r.getCreatedAt());
        }
    }

    public record ApprovalResponse(CollectResponse request, PaymentResponse payment) {
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Request money from another VPA")
    public CollectResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateCollectRequest req) {
        UUID me = Caller.from(jwt).userId();
        return CollectResponse.of(collects.create(me, req.requesterVpa(), req.payerVpa(), req.amountPaise(), req.note(),
                req.expiresInMinutes()), me, collects.now());
    }

    @GetMapping
    @Operation(summary = "Collect requests: direction=incoming (you are asked to pay) or outgoing (you asked)")
    public List<CollectResponse> list(@AuthenticationPrincipal Jwt jwt,
                                      @RequestParam(defaultValue = "incoming") String direction,
                                      @RequestParam(defaultValue = "50") int limit) {
        UUID me = Caller.from(jwt).userId();
        Instant now = collects.now();
        List<CollectRequest> rows = "outgoing".equalsIgnoreCase(direction) ? collects.outgoing(me, limit) : collects.incoming(me, limit);
        return rows.stream().map(r -> CollectResponse.of(r, me, now)).toList();
    }

    @PostMapping("/{id}/approve")
    @Operation(summary = "Approve and pay (idempotent: at most one payment per request)")
    public ApprovalResponse approve(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        Caller caller = Caller.from(jwt);
        CollectService.Decision d = collects.approve(caller.userId(), caller.isAdminDemo(), id);
        return new ApprovalResponse(CollectResponse.of(d.request(), caller.userId(), collects.now()),
                PaymentResponse.of(d.payment(), null, caller.userId()));
    }

    @PostMapping("/{id}/decline")
    public CollectResponse decline(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        UUID me = Caller.from(jwt).userId();
        return CollectResponse.of(collects.decline(me, id), me, collects.now());
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Requester withdraws a pending request")
    public CollectResponse cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        UUID me = Caller.from(jwt).userId();
        return CollectResponse.of(collects.cancel(me, id), me, collects.now());
    }
}
