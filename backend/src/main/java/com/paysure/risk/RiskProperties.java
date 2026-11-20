package com.paysure.risk;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Risk rule thresholds and weights. Everything is configurable; the defaults are teaching values.
 *
 * @param enabled              evaluate payments at all
 * @param mode                 {@code ALERT} (default): record alerts and events but never refuse a payment.
 *                             {@code ENFORCE}: refuse payments whose score reaches {@code blockScore}.
 * @param alertScore           scores at or above this are recorded as alerts (and published as events)
 * @param highScore            scores at or above this are level HIGH
 * @param blockScore           scores at or above this suggest BLOCK (acted on only in ENFORCE mode)
 * @param velocityThreshold    transfers (including this one) in {@code velocityWindowSeconds} that count as high velocity
 * @param repeatThreshold      transfers to the same payee in {@code repeatWindowSeconds} that count as rapid repeats
 * @param failureThreshold     failed payments in {@code failureWindowSeconds} that count as "multiple failures"
 * @param largeAmountPaise     an amount at or above this is "unusually large" for the sandbox
 * @param burstThreshold       transfers in {@code burstWindowSeconds} that make a new recipient part of a burst
 */
@ConfigurationProperties("paysure.risk")
public record RiskProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("ALERT") Mode mode,
        @DefaultValue("40") int alertScore,
        @DefaultValue("70") int highScore,
        @DefaultValue("90") int blockScore,
        @DefaultValue("8") int velocityThreshold,
        @DefaultValue("60") int velocityWindowSeconds,
        @DefaultValue("35") int velocityPoints,
        @DefaultValue("3") int repeatThreshold,
        @DefaultValue("60") int repeatWindowSeconds,
        @DefaultValue("20") int repeatPoints,
        @DefaultValue("3") int failureThreshold,
        @DefaultValue("600") int failureWindowSeconds,
        @DefaultValue("20") int failurePoints,
        @DefaultValue("5000000") long largeAmountPaise,
        @DefaultValue("25") int largeAmountPoints,
        @DefaultValue("15") int newRecipientPoints,
        @DefaultValue("3") int burstThreshold,
        @DefaultValue("300") int burstWindowSeconds,
        @DefaultValue("20") int burstPoints) {

    public enum Mode { ALERT, ENFORCE }
}
