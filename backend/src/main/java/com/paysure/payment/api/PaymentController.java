package com.paysure.payment.api;

import com.paysure.bank.BankContracts.SimulationOptions;
import com.paysure.common.error.ForbiddenException;
import com.paysure.identity.application.Caller;
import com.paysure.payment.api.PaymentDtos.PaymentDetailResponse;
import com.paysure.payment.api.PaymentDtos.PaymentRequest;
import com.paysure.payment.api.PaymentDtos.PaymentResponse;
import com.paysure.payment.api.PaymentDtos.StatusCheckResponse;
import com.paysure.payment.application.PaymentCommand;
import com.paysure.payment.application.PaymentQueryService;
import com.paysure.payment.application.PaymentQueryService.PaymentDetail;
import com.paysure.payment.application.PaymentService;
import com.paysure.payment.application.PaymentStatusVerifier;
import com.paysure.payment.domain.Payment;
import com.paysure.payment.domain.PaymentStatus;
import com.paysure.payment.domain.TransitionSource;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payments")
@Tag(name = "Payments")
public class PaymentController {

    public static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    public static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private final PaymentService paymentService;
    private final PaymentQueryService queries;
    private final PaymentStatusVerifier verifier;

    public PaymentController(PaymentService paymentService, PaymentQueryService queries, PaymentStatusVerifier verifier) {
        this.paymentService = paymentService;
        this.queries = queries;
        this.verifier = verifier;
    }

    @PostMapping
    @Operation(summary = "Send a simulated payment",
            description = "Requires an Idempotency-Key. Repeating the same key and body returns the same payment "
                    + "(200, Idempotent-Replayed: true); the same key with a different body returns 409.")
    public ResponseEntity<PaymentResponse> create(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "Client-generated unique key, e.g. a UUID") @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String key,
            @Valid @RequestBody PaymentRequest req) {
        Caller caller = Caller.from(jwt);
        SimulationOptions sim = req.simulation() == null ? null : new SimulationOptions(req.simulation().scenario(),
                req.simulation().delaySeconds() == null ? 0 : req.simulation().delaySeconds(),
                req.simulation().duplicateCallbacks() == null ? 1 : req.simulation().duplicateCallbacks());
        PaymentService.Initiated result = paymentService.initiate(new PaymentCommand(caller.userId(), caller.isAdminDemo(),
                key, req.payerVpa(), req.payeeVpa(), req.amountPaise(), req.note(), sim, null));
        PaymentDetail detail = queries.detail(caller, result.payment().getId());
        PaymentResponse body = PaymentDetailResponse.of(detail.payment(), detail.history(), detail.attempts(), null,
                caller.userId()).payment();
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .header(REPLAYED_HEADER, Boolean.toString(result.replayed()))
                .body(body);
    }

    @GetMapping
    @Operation(summary = "Payments sent or received by the caller, newest first")
    public List<PaymentResponse> list(@AuthenticationPrincipal Jwt jwt,
                                      @RequestParam(required = false) PaymentStatus status,
                                      @RequestParam(defaultValue = "50") int limit) {
        UUID me = Caller.from(jwt).userId();
        return queries.list(me, status, limit).stream()
                .map(s -> PaymentResponse.of(s.payment(), s.bankReference(), me)).toList();
    }

    @GetMapping("/{paymentId}")
    @Operation(summary = "Payment details: status history, bank attempts and the simulated bank's view")
    public PaymentDetailResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID paymentId) {
        Caller caller = Caller.from(jwt);
        PaymentDetail d = queries.detail(caller, paymentId);
        return PaymentDetailResponse.of(d.payment(), d.history(), d.attempts(), d.bankSide().orElse(null), caller.userId());
    }

    @GetMapping("/{paymentId}/status")
    @Operation(summary = "Lightweight payment status for polling (Redis read-through cache; X-Cache: HIT|MISS)")
    public ResponseEntity<PaymentDtos.StatusResponse> status(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID paymentId) {
        PaymentQueryService.StatusView v = queries.status(Caller.from(jwt), paymentId);
        var e = v.entry();
        return ResponseEntity.ok().header("X-Cache", v.fromCache() ? "HIT" : "MISS")
                .body(new PaymentDtos.StatusResponse(e.paymentId(), PaymentStatus.valueOf(e.status()),
                        com.paysure.payment.domain.HoldStatus.valueOf(e.holdStatus()), e.failureCode(), e.amountPaise(), e.updatedAt()));
    }

    @PostMapping("/{paymentId}/check-status")
    @Operation(summary = "Ask the bank for the authoritative status (never re-sends the transfer)")
    public StatusCheckResponse checkStatus(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID paymentId) {
        Caller caller = Caller.from(jwt);
        Payment visible = queries.requireVisible(caller, paymentId);
        if (!visible.getPayerUserId().equals(caller.userId()) && !caller.isAdminDemo()) {
            throw new ForbiddenException("FORBIDDEN", "Only the payer can trigger a status check.");
        }
        PaymentStatusVerifier.Verification v = verifier.verify(paymentId, TransitionSource.STATUS_CHECK);
        PaymentDetail d = queries.detail(caller, paymentId);
        return new StatusCheckResponse(v.effect(), v.bankStatus(),
                PaymentDetailResponse.of(d.payment(), d.history(), d.attempts(), null, caller.userId()).payment());
    }
}
