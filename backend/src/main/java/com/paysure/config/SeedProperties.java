package com.paysure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Demo data seeding. The password comes from configuration/environment only; nothing is hardcoded
 * outside the explicitly-local {@code dev} profile.
 */
@ConfigurationProperties("paysure.seed")
public record SeedProperties(boolean enabled, String demoPassword) {
}
