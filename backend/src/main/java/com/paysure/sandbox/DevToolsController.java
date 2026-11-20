package com.paysure.sandbox;

import com.paysure.account.api.AccountDtos.AccountResponse;
import com.paysure.account.api.AccountDtos.TopUpRequest;
import com.paysure.account.application.AccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.paysure.bank.simulator.SeededFaultPlanner;
import com.paysure.bank.simulator.SimulatedBank;
import jakarta.validation.Valid;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Development-only sandbox tools. The bean does not exist unless
 * {@code paysure.sandbox.dev-tools-enabled=true}, and every call requires ADMIN_DEMO
 * (enforced both by the URL rule in SecurityConfig and here).
 */
@RestController
@RequestMapping("/api/dev")
@ConditionalOnProperty(prefix = "paysure.sandbox", name = "dev-tools-enabled", havingValue = "true")
@PreAuthorize("hasRole('ADMIN_DEMO')")
@Tag(name = "Sandbox dev tools")
public class DevToolsController {

    private final AccountService accountService;
    private final SimulatedBank bank;
    private final SeededFaultPlanner planner;
    private final Clock clock;

    public DevToolsController(AccountService accountService, SimulatedBank bank, SeededFaultPlanner planner, Clock clock) {
        this.accountService = accountService;
        this.bank = bank;
        this.planner = planner;
        this.clock = clock;
    }

    @PostMapping("/accounts/{accountId}/top-up")
    @Operation(summary = "Credit simulated money from the sandbox treasury (balanced ledger journal)")
    public AccountResponse topUp(@PathVariable UUID accountId, @Valid @RequestBody TopUpRequest req) {
        return AccountResponse.of(accountService.sandboxTopUp(accountId, req.amountPaise(), req.reason()));
    }

    @PostMapping("/bank/tick")
    @Operation(summary = "Advance the simulated bank now: resolve due delayed transfers and deliver due callbacks",
            description = "skipAheadSeconds lets a demo fast-forward the bank's clock instead of waiting.")
    public SimulatedBank.TickResult tick(@RequestParam(defaultValue = "0") long skipAheadSeconds) {
        long skip = Math.max(0, Math.min(skipAheadSeconds, 3600));
        return bank.tick(clock.instant().plusSeconds(skip));
    }

    @GetMapping("/bank/chaos")
    @Operation(summary = "Seeded (reproducible) chaos mode status")
    public Map<String, Object> chaos() {
        return Map.of("enabled", planner.enabled(), "seed", planner.seed());
    }

    @PostMapping("/bank/chaos/reset")
    @Operation(summary = "Restart the seeded fault sequence so a demo can be replayed exactly")
    public Map<String, Object> resetChaos(@RequestParam(defaultValue = "42") long seed) {
        planner.reset(seed);
        return chaos();
    }
}
