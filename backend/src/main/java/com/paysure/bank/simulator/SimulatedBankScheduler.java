package com.paysure.bank.simulator;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** The simulated bank's clock: resolves delayed transfers and delivers callbacks in the background. */
@Component
@ConditionalOnProperty(prefix = "paysure.bank", name = "scheduler-enabled", havingValue = "true", matchIfMissing = true)
public class SimulatedBankScheduler {

    private static final Logger log = LoggerFactory.getLogger(SimulatedBankScheduler.class);

    private final SimulatedBank bank;
    private final Clock clock;

    public SimulatedBankScheduler(SimulatedBank bank, Clock clock) {
        this.bank = bank;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${paysure.bank.tick-millis:1000}", initialDelay = 2000)
    void tick() {
        try {
            SimulatedBank.TickResult r = bank.tick(clock.instant());
            if (r.transfersResolved() + r.callbacksDelivered() + r.callbacksFailed() > 0) {
                log.info("Bank tick: resolved={} delivered={} failed={}", r.transfersResolved(), r.callbacksDelivered(),
                        r.callbacksFailed());
            }
        } catch (RuntimeException e) {
            log.error("Bank tick failed", e);
        }
    }
}
