package com.paysure.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Sandbox rules. All amounts are integral paise.
 *
 * @param vpaSuffix          handle suffix after '@' (e.g. {@code paysure} in {@code ananya@paysure})
 * @param seedBalancePaise   simulated money credited to every newly created account
 * @param maxAccountsPerUser upper bound of sandbox accounts per user
 * @param maxVpasPerAccount  upper bound of VPAs linked to one account
 * @param devToolsEnabled    exposes {@code /api/dev/**} (ADMIN_DEMO only); must be false outside local demos
 * @param maxTopUpPaise      cap for a single dev top-up
 */
@Validated
@ConfigurationProperties("paysure.sandbox")
public record SandboxProperties(
        @NotBlank @Pattern(regexp = "^[a-z]{3,20}$") String vpaSuffix,
        @Min(0) long seedBalancePaise,
        @Min(1) int maxAccountsPerUser,
        @Min(1) int maxVpasPerAccount,
        boolean devToolsEnabled,
        @Min(1) long maxTopUpPaise) {
}
