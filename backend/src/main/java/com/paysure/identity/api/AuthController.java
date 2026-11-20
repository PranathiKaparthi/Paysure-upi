package com.paysure.identity.api;

import com.paysure.identity.api.AuthDtos.LoginRequest;
import com.paysure.identity.api.AuthDtos.RegisterRequest;
import com.paysure.identity.api.AuthDtos.TokenResponse;
import com.paysure.identity.api.AuthDtos.UserResponse;
import com.paysure.identity.application.AuthService;
import com.paysure.identity.application.Caller;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Authentication")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @SecurityRequirements
    @Operation(summary = "Register a sandbox user (always role USER)")
    public UserResponse register(@Valid @RequestBody RegisterRequest request) {
        return UserResponse.of(authService.register(request.email(), request.password(), request.fullName()));
    }

    @PostMapping("/login")
    @SecurityRequirements
    @Operation(summary = "Exchange e-mail and password for a bearer access token")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        AuthService.LoginResult result = authService.login(request.email(), request.password());
        return new TokenResponse(result.token().value(), "Bearer", result.token().expiresInSeconds(),
                UserResponse.of(result.user()));
    }

    @GetMapping("/me")
    @Operation(summary = "Current authenticated user")
    public UserResponse me(@AuthenticationPrincipal Jwt jwt) {
        return UserResponse.of(authService.getUser(Caller.from(jwt).userId()));
    }
}
