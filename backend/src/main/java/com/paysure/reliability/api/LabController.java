package com.paysure.reliability.api;

import com.paysure.identity.application.Caller;
import com.paysure.reliability.LabService;
import com.paysure.reliability.LabService.Action;
import com.paysure.reliability.LabService.LabView;
import com.paysure.reliability.LabService.RunListItem;
import com.paysure.reliability.LabService.Scenario;
import com.paysure.reliability.LabService.StartCommand;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
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

/** Reliability Lab. ADMIN_DEMO only; every fault is confined to the simulated bank and sandbox accounts. */
@RestController
@RequestMapping("/api/admin/lab")
@PreAuthorize("hasRole('ADMIN_DEMO')")
@Tag(name = "Reliability Lab")
public class LabController {

    private final LabService lab;

    public LabController(LabService lab) {
        this.lab = lab;
    }

    public record StartRequest(@NotBlank @Size(max = 40) String scenario,
                               @Positive @Max(500_000) Long amountPaise,
                               @Min(0) @Max(600) Integer delaySeconds,
                               @Min(1) @Max(10) Integer duplicateCallbacks,
                               @Size(max = 64) String payerVpa,
                               @Size(max = 64) String payeeVpa) {
    }

    public record ActionRequest(@NotNull Action action, @Min(1) @Max(10) Integer repeat) {
    }

    @GetMapping("/scenarios")
    @Operation(summary = "Catalogue of failure scenarios (from the fault_scenarios table)")
    public List<Scenario> scenarios() {
        return lab.scenarios();
    }

    @PostMapping("/runs")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Start a simulation: inject the fault into a sandbox payment")
    public LabView start(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody StartRequest req) {
        return lab.start(new StartCommand(Caller.from(jwt).userId(), req.scenario(), req.amountPaise(), req.delaySeconds(),
                req.duplicateCallbacks(), req.payerVpa(), req.payeeVpa()));
    }

    @GetMapping("/runs")
    public List<RunListItem> runs(@RequestParam(defaultValue = "10") int limit) {
        return lab.runs(limit);
    }

    @GetMapping("/runs/{id}")
    @Operation(summary = "Timeline, balances, ledger, bank view, reconciliation result and explanation of a run")
    public LabView get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return lab.view(id, Caller.from(jwt).userId());
    }

    @PostMapping("/runs/{id}/actions")
    @Operation(summary = "RECONCILE (run the worker now), FAST_FORWARD (advance the simulated bank clock) or RESTORE_STATUS_API")
    public LabView act(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody ActionRequest req) {
        return lab.act(id, Caller.from(jwt).userId(), req.action(), req.repeat() == null ? 1 : req.repeat());
    }
}
