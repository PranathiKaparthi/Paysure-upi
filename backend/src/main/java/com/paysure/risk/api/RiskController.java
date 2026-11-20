package com.paysure.risk.api;

import com.paysure.account.domain.Account;
import com.paysure.account.domain.UpiHandle;
import com.paysure.account.domain.Vpa;
import com.paysure.account.repository.AccountRepository;
import com.paysure.account.repository.UpiHandleRepository;
import com.paysure.common.error.BusinessRuleException;
import com.paysure.common.error.ForbiddenException;
import com.paysure.common.error.NotFoundException;
import com.paysure.identity.application.Caller;
import com.paysure.risk.RiskEngine.Assessment;
import com.paysure.risk.RiskProperties;
import com.paysure.risk.RiskService;
import com.paysure.risk.RiskService.Alert;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@Tag(name = "Risk (educational simulation)")
public class RiskController {

    private final RiskService risk;
    private final UpiHandleRepository handles;
    private final AccountRepository accounts;

    public RiskController(RiskService risk, UpiHandleRepository handles, AccountRepository accounts) {
        this.risk = risk;
        this.handles = handles;
        this.accounts = accounts;
    }

    public record AssessRequest(@NotBlank @Size(max = 64) String payerVpa, @NotBlank @Size(max = 64) String payeeVpa,
                                @NotNull @Positive @Max(10_000_000L) Long amountPaise) {
    }

    public record ReviewRequest(@NotBlank @Pattern(regexp = "ACKNOWLEDGED|DISMISSED") String status) {
    }

    public record AssessmentResponse(int score, String level, String action, List<RuleView> rules, List<String> explanation,
                                     String suggestedAction, boolean wouldAlert, boolean wouldBlock, String mode, String disclaimer) {
    }

    public record RuleView(String code, String label, int points, String detail) {
    }

    @GetMapping("/risk/config")
    @Operation(summary = "Active rule thresholds and mode")
    public Map<String, Object> config() {
        RiskProperties p = risk.properties();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", p.enabled());
        m.put("mode", p.mode());
        m.put("alertScore", p.alertScore());
        m.put("highScore", p.highScore());
        m.put("blockScore", p.blockScore());
        m.put("rules", List.of(
                Map.of("code", "HIGH_VELOCITY", "points", p.velocityPoints(), "threshold", p.velocityThreshold(), "windowSeconds", p.velocityWindowSeconds()),
                Map.of("code", "RAPID_REPEAT", "points", p.repeatPoints(), "threshold", p.repeatThreshold(), "windowSeconds", p.repeatWindowSeconds()),
                Map.of("code", "MULTIPLE_FAILURES", "points", p.failurePoints(), "threshold", p.failureThreshold(), "windowSeconds", p.failureWindowSeconds()),
                Map.of("code", "LARGE_AMOUNT", "points", p.largeAmountPoints(), "thresholdPaise", p.largeAmountPaise()),
                Map.of("code", "NEW_RECIPIENT", "points", p.newRecipientPoints()),
                Map.of("code", "NEW_RECIPIENT_BURST", "points", p.burstPoints(), "threshold", p.burstThreshold(), "windowSeconds", p.burstWindowSeconds())));
        m.put("disclaimer", RiskService.DISCLAIMER);
        return m;
    }

    @PostMapping("/risk/assess")
    @Operation(summary = "What-if: how would this payment score right now? Nothing is recorded or sent.")
    public AssessmentResponse assess(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody AssessRequest req) {
        Caller caller = Caller.from(jwt);
        UpiHandle payerHandle = handles.findByVpa(Vpa.parse(req.payerVpa()).value()).filter(UpiHandle::isActive).orElse(null);
        Account payer = payerHandle == null ? null : accounts.findById(payerHandle.getAccountId()).orElse(null);
        if (payer == null || !payer.isOwnedBy(caller.userId())) {
            throw new ForbiddenException("PAYER_NOT_OWNED", "You can only assess payments from your own VPA.");
        }
        Account payee = handles.findByVpa(Vpa.parse(req.payeeVpa()).value()).filter(UpiHandle::isActive)
                .flatMap(h -> accounts.findById(h.getAccountId()))
                .orElseThrow(() -> new NotFoundException("PAYEE_NOT_FOUND", "Payee VPA not found or not active."));
        if (payee.getId().equals(payer.getId())) {
            throw new BusinessRuleException("SELF_TRANSFER_NOT_ALLOWED", "Payer and payee resolve to the same account.");
        }
        Assessment a = risk.assess(caller.userId(), payee.getId(), req.amountPaise());
        return new AssessmentResponse(a.score(), a.level().name(), a.action().name(),
                a.rules().stream().map(r -> new RuleView(r.code(), r.label(), r.points(), r.detail())).toList(), a.explanation(),
                a.suggestedAction(), a.alertWorthy(), risk.shouldBlock(a), risk.properties().mode().name(), RiskService.DISCLAIMER);
    }

    @GetMapping("/risk/alerts")
    @Operation(summary = "The caller's own risk alerts (newest first)")
    public List<Alert> myAlerts(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) String status,
                                @RequestParam(required = false) UUID paymentId, @RequestParam(defaultValue = "50") int limit) {
        return risk.list(Caller.from(jwt).userId(), status, paymentId, limit);
    }

    @GetMapping("/admin/risk/alerts")
    @PreAuthorize("hasRole('ADMIN_DEMO')")
    @Operation(summary = "All users' risk alerts (demo admins)")
    public List<Alert> allAlerts(@RequestParam(required = false) String status, @RequestParam(defaultValue = "100") int limit) {
        return risk.list(null, status, null, limit);
    }

    @PostMapping("/admin/risk/alerts/{id}/review")
    @PreAuthorize("hasRole('ADMIN_DEMO')")
    @Operation(summary = "Acknowledge or dismiss an open alert")
    public Alert review(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody ReviewRequest req) {
        return risk.review(id, Caller.from(jwt).userId(), req.status());
    }
}
