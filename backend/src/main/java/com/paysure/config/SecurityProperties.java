package com.paysure.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("paysure.security")
public record SecurityProperties(
        @Valid @NotNull Jwt jwt,
        List<String> corsOrigins,
        @DefaultValue("12") @Min(4) @Max(16) int bcryptStrength) {

    /**
     * @param secret     HMAC-SHA256 key material; validated to be at least 32 bytes at startup
     * @param ttlMinutes access-token lifetime
     * @param issuer     {@code iss} claim written and required on every token
     */
    public record Jwt(String secret, @Min(1) @Max(1440) long ttlMinutes, @NotBlank String issuer) {
    }
}
