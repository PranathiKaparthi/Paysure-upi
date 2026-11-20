package com.paysure.reconciliation;

import com.paysure.reconciliation.ReconciliationModel.TriggerType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs the reconciliation worker periodically. Multiple instances are safe: payments are claimed with leases. */
@Component
@ConditionalOnProperty(prefix = "paysure.reconciliation", name = "scheduler-enabled", havingValue = "true", matchIfMissing = true)
public class ReconciliationScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationScheduler.class);

    private final ReconciliationWorker worker;

    public ReconciliationScheduler(ReconciliationWorker worker) {
        this.worker = worker;
    }

    @Scheduled(fixedDelayString = "${paysure.reconciliation.interval-millis:15000}", initialDelay = 10000)
    void tick() {
        try {
            worker.run(TriggerType.SCHEDULED);
        } catch (RuntimeException e) {
            log.error("Scheduled reconciliation run failed", e);
        }
    }
}
