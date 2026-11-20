package com.paysure.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.paysure.payment.application.IdempotencyCleanupJob;
import com.paysure.payment.repository.IdempotencyRecordRepository;
import com.paysure.support.PaymentTestBase;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

class IdempotencyRetentionTest extends PaymentTestBase {

    @Autowired
    IdempotencyRecordRepository records;
    @Autowired
    TransactionTemplate tx;

    @Test
    void expiredKeysOfFinishedPaymentsArePurgedButInFlightOnesAreKept() throws Exception {
        Party lab = adminParty();
        Party payee = party("ret");
        String doneKey = key();
        String pendingKey = key();
        pay(lab, payee.vpa(), 100, doneKey).andExpect(jsonPath("$.status").value("SUCCESS"));
        pay(lab, payee.vpa(), 100, pendingKey, scenario("TIMEOUT_AFTER_COMMIT")).andExpect(jsonPath("$.status").value("PENDING"));
        jdbc.update("update idempotency_records set expires_at = now() - interval '1 hour' where caller_user_id = ?", lab.user().id());

        // The scheduler is disabled in tests; run the job body inside a transaction.
        tx.executeWithoutResult(s -> new IdempotencyCleanupJob(records, Clock.systemUTC()).purgeExpired());

        assertThat(records.findByCallerUserIdAndIdempotencyKey(lab.user().id(), doneKey)).isEmpty();
        // Never forget a key while its payment's outcome is unknown.
        assertThat(records.findByCallerUserIdAndIdempotencyKey(lab.user().id(), pendingKey)).isPresent();

        // After expiry the key may be reused for a new payment.
        pay(lab, payee.vpa(), 100, doneKey).andExpect(status().isCreated());
        assertLedgerInvariants();
    }
}
