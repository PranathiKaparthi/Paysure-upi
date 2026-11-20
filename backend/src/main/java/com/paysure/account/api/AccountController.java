package com.paysure.account.api;

import com.paysure.account.api.AccountDtos.AccountResponse;
import com.paysure.account.api.AccountDtos.BalanceResponse;
import com.paysure.account.api.AccountDtos.CreateAccountRequest;
import com.paysure.account.api.AccountDtos.LedgerEntryResponse;
import com.paysure.account.api.AccountDtos.LinkVpaRequest;
import com.paysure.account.api.AccountDtos.VpaResponse;
import com.paysure.account.application.AccountService;
import com.paysure.account.application.VpaService;
import com.paysure.identity.application.Caller;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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

/** Thin HTTP adapter: the caller id always comes from the verified JWT, never from the request body. */
@RestController
@RequestMapping("/api/accounts")
@Tag(name = "Accounts")
public class AccountController {

    private final AccountService accountService;
    private final VpaService vpaService;

    public AccountController(AccountService accountService, VpaService vpaService) {
        this.accountService = accountService;
        this.vpaService = vpaService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Open a sandbox account (funded with the configured demo balance)")
    public AccountResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateAccountRequest req) {
        return AccountResponse.of(accountService.openAccount(Caller.from(jwt).userId(), req.displayName(), req.vpaHandle()));
    }

    @GetMapping
    @Operation(summary = "List the caller's accounts")
    public List<AccountResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return accountService.listAccounts(Caller.from(jwt).userId()).stream().map(AccountResponse::of).toList();
    }

    @GetMapping("/{accountId}")
    public AccountResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID accountId) {
        return AccountResponse.of(accountService.getAccount(Caller.from(jwt).userId(), accountId));
    }

    @GetMapping("/{accountId}/balance")
    @Operation(summary = "Balance inquiry: ledger balance, reserved (in-flight) and available funds")
    public BalanceResponse balance(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID accountId) {
        return BalanceResponse.of(accountService.getAccount(Caller.from(jwt).userId(), accountId).account());
    }

    @GetMapping("/{accountId}/ledger")
    @Operation(summary = "Most recent immutable ledger entries for the account")
    public List<LedgerEntryResponse> ledger(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID accountId,
                                            @RequestParam(defaultValue = "20") int limit) {
        return accountService.recentLedger(Caller.from(jwt).userId(), accountId, limit).stream()
                .map(LedgerEntryResponse::of).toList();
    }

    @PostMapping("/{accountId}/deactivate")
    public AccountResponse deactivate(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID accountId) {
        return AccountResponse.of(accountService.deactivate(Caller.from(jwt).userId(), accountId));
    }

    @PostMapping("/{accountId}/activate")
    public AccountResponse activate(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID accountId) {
        return AccountResponse.of(accountService.activate(Caller.from(jwt).userId(), accountId));
    }

    @PostMapping("/{accountId}/vpas")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Link a new unique VPA (handle@paysure) to the account")
    public VpaResponse linkVpa(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID accountId,
                               @Valid @RequestBody LinkVpaRequest req) {
        return VpaResponse.of(vpaService.link(Caller.from(jwt).userId(), accountId, req.handle()));
    }
}
