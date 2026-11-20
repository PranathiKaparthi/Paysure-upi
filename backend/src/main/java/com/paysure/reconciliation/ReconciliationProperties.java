package com.paysure.reconciliation;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param schedulerEnabled       run the worker on a schedule
 * @param intervalMillis         delay between scheduled runs
 * @param minAge                 in-flight payments younger than this are left to the normal flow (callbacks)
 * @param batchSize              max in-flight payments claimed per run
 * @param lease                  how long a claim is valid; a crashed worker's claims expire after this
 * @param escalateAfterAttempts  unresolved checks before a payment is flagged for manual review
 * @param escalateAfterAge       ...or once the payment is this old without a final outcome
 * @param postSettlementWindow   finished payments completed within this window are re-verified once
 * @param postSettlementBatch    max finished payments verified per run
 */
@ConfigurationProperties("paysure.reconciliation")
public record ReconciliationProperties(
        @DefaultValue("true") boolean schedulerEnabled,
        @DefaultValue("15000") long intervalMillis,
        @DefaultValue("10s") Duration minAge,
        @DefaultValue("50") int batchSize,
        @DefaultValue("60s") Duration lease,
        @DefaultValue("5") int escalateAfterAttempts,
        @DefaultValue("10m") Duration escalateAfterAge,
        @DefaultValue("24h") Duration postSettlementWindow,
        @DefaultValue("100") int postSettlementBatch) {
}
