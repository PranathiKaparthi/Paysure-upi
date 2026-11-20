package com.paysure.bank.simulator;

import com.paysure.bank.SimulationScenario;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param defaultScenario     scenario used when a payment does not request one
 * @param defaultDelaySeconds delay used by delayed/callback scenarios when the request gives 0
 * @param latencyMs           artificial latency of each submit call (makes demos feel realistic)
 * @param schedulerEnabled    run the background clock that resolves delayed transfers and delivers callbacks
 * @param tickMillis          scheduler period
 * @param seededChaos         reproducible random fault injection for demos
 */
@ConfigurationProperties("paysure.bank")
public record BankSimulatorProperties(
        @DefaultValue("NORMAL_SUCCESS") SimulationScenario defaultScenario,
        @DefaultValue("5") int defaultDelaySeconds,
        @DefaultValue("0") long latencyMs,
        @DefaultValue("true") boolean schedulerEnabled,
        @DefaultValue("1000") long tickMillis,
        @DefaultValue SeededChaos seededChaos) {

    /**
     * When enabled, requests without an explicit scenario get a fault scenario with probability
     * {@code faultRate}, chosen from a {@code java.util.Random(seed)} stream: the same sequence of
     * requests after a reset produces the same sequence of faults.
     */
    public record SeededChaos(@DefaultValue("false") boolean enabled,
                              @DefaultValue("42") long seed,
                              @DefaultValue("0.3") double faultRate) {
    }
}
