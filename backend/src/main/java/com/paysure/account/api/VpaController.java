package com.paysure.account.api;

import com.paysure.account.api.AccountDtos.ResolveVpaResponse;
import com.paysure.account.api.AccountDtos.VpaResponse;
import com.paysure.account.application.VpaService;
import com.paysure.identity.application.Caller;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/vpas")
@Tag(name = "VPAs")
public class VpaController {

    private final VpaService vpaService;

    public VpaController(VpaService vpaService) {
        this.vpaService = vpaService;
    }

    @GetMapping("/{vpa}/resolve")
    @Operation(summary = "Resolve a payee VPA to the registered name (no account details are revealed)")
    public ResolveVpaResponse resolve(@PathVariable String vpa) {
        return ResolveVpaResponse.of(vpaService.resolve(vpa));
    }

    @PostMapping("/{vpa}/deactivate")
    @Operation(summary = "Deactivate one of the caller's VPAs")
    public VpaResponse deactivate(@AuthenticationPrincipal Jwt jwt, @PathVariable String vpa) {
        return VpaResponse.of(vpaService.deactivate(Caller.from(jwt).userId(), vpa));
    }
}
