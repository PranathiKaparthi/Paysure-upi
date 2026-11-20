package com.paysure.reliability;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configurable DEMO scoring parameters. These are teaching defaults, not an industry benchmark.
 *
 * @param windowMinutes               default look-back window
 * @param pendingAgeBudgetSeconds     a payment unresolved this long scores 0 on "pending age"
 * @param latencyGoodSeconds          P95 completion latency at or below this scores 100
 * @param latencyBadSeconds           P95 completion latency at or above this scores 0
 * @param timeoutRateBudget           a timeout/network-error rate at or above this scores 0
 * @param snapshotSchedulerEnabled    store a score snapshot periodically (feeds the history chart)
 * @param snapshotIntervalMillis      snapshot period
 */
@ConfigurationProperties("paysure.reliability")
public record ReliabilityProperties(
        @DefaultValue("60") int windowMinutes,
        @DefaultValue("900") int pendingAgeBudgetSeconds,
        @DefaultValue("2") int latencyGoodSeconds,
        @DefaultValue("60") int latencyBadSeconds,
        @DefaultValue("0.25") double timeoutRateBudget,
        @DefaultValue("true") boolean snapshotSchedulerEnabled,
        @DefaultValue("300000") long snapshotIntervalMillis,
        @DefaultValue Weights weights) {

    /** Relative weights; components without data are left out and the rest are re-normalised. */
    public record Weights(@DefaultValue("30") int successRate,
                          @DefaultValue("15") int pendingAge,
                          @DefaultValue("10") int latency,
                          @DefaultValue("10") int timeoutRate,
                          @DefaultValue("15") int recoveryRate,
                          @DefaultValue("10") int duplicatePrevention,
                          @DefaultValue("10") int webhookDelivery) {
    }
}
