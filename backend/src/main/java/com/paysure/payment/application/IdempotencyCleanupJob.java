package com.paysure.payment.application;

import com.paysure.payment.repository.IdempotencyRecordRepository;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Retention policy: an Idempotency-Key is remembered for at least {@code paysure.payments.idempotency-ttl}
 * (24h) and is never forgotten while its payment is still PROCESSING/PENDING. After deletion the same key
 * may create a new payment.
 */
@Component
@ConditionalOnProperty(prefix = "paysure.payments", name = "cleanup-enabled", havingValue = "true", matchIfMissing = true)
public class IdempotencyCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyCleanupJob.class);

    private final IdempotencyRecordRepository records;
    private final Clock clock;

    public IdempotencyCleanupJob(IdempotencyRecordRepository records, Clock clock) {
        this.records = records;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "PT15M", initialDelayString = "PT1M")
    @Transactional
    public void purgeExpired() {
        int deleted = records.deleteExpired(clock.instant());
        if (deleted > 0) {
            log.info("Purged {} expired idempotency record(s)", deleted);
        }
    }
}
