package com.paysure.reliability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.paysure.reliability.ReliabilityScoreService.Component;
import com.paysure.reliability.ReliabilityScoreService.Score;
import com.paysure.support.ReliabilityTestBase;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The score is computed only from stored events. Each test uses a window that starts "now", so it sees only the
 * payments it creates itself (test classes run sequentially).
 */
class ReliabilityScoreTest extends ReliabilityTestBase {

    @Autowired
    ReliabilityScoreService scores;

    private static Map<String, Component> byKey(Score s) {
        return s.components().stream().collect(Collectors.toMap(Component::key, c -> c));
    }

    @Test
    void emptyWindowHasNoScoreRatherThanAPerfectOne() {
        Score s = scores.compute(Instant.now().plusSeconds(3600));
        assertThat(s.score()).isNull();
        assertThat(s.components()).hasSize(7).noneMatch(Component::available);
        assertThat(s.disclaimer()).contains("not an industry-standard benchmark");
    }

    @Test
    void webhookDeliveryIsExcludedWhenThereAreNoFinishedDeliveriesAndMeasuredFromStoredRowsOtherwise() throws Exception {
        Instant from = Instant.now();
        Party lab = adminParty();
        Party payee = party("sc0");
        pay(lab, payee.vpa(), 100, key());
        Component none = byKey(scores.compute(from)).get("webhook_delivery");
        assertThat(none.available()).isFalse();
        assertThat(none.score()).isNull();
        assertThat(none.valueText()).contains("No finished webhook deliveries");

        // 3 delivered (one after retries) and 1 dead-lettered delivery, stored like the worker stores them.
        java.util.UUID endpoint = java.util.UUID.randomUUID();
        jdbc.update("insert into webhook_endpoints (id, owner_user_id, url) values (?, ?, 'https://93.184.216.34/hook')", endpoint, lab.user().id());
        for (int i = 0; i < 4; i++) {
            boolean ok = i < 3;
            jdbc.update("""
                    insert into webhook_deliveries (id, endpoint_id, event_id, event_type, payload, status, attempts, max_attempts,
                        next_attempt_at, delivered_at) values (?, ?, ?, 'payment.completed', '{}', ?, ?, 6, now(), ?)""",
                    java.util.UUID.randomUUID(), endpoint, java.util.UUID.randomUUID(), ok ? "SUCCESS" : "DEAD", i == 0 ? 3 : 1,
                    ok ? java.sql.Timestamp.from(Instant.now()) : null);
        }
        Component wh = byKey(scores.compute(from)).get("webhook_delivery");
        assertThat(wh.available()).isTrue();
        assertThat(wh.score()).isEqualTo(75);
        assertThat(wh.valueText()).contains("3 delivered").contains("1 dead-lettered").contains("1 needed retries");
    }

    @Test
    void scoreDropsAfterAFaultAndRisesAgainAfterReconciliationRecovery() throws Exception {
        Instant from = Instant.now();
        Party lab = adminParty();
        Party payee = party("sc1");

        for (int i = 0; i < 3; i++) {
            pay(lab, payee.vpa(), 1_000, key()).andExpect(status().isCreated());
        }
        Score healthy = scores.compute(from);
        assertThat(healthy.score()).isEqualTo(100);
        assertThat(byKey(healthy).get("success_rate").valueText()).contains("3 of 3");
        assertThat(byKey(healthy).get("recovery_rate").available()).isFalse(); // nothing ever went PENDING

        // Fault: a lost acknowledgement leaves one payment PENDING.
        pay(lab, payee.vpa(), 1_000, key(), scenario("TIMEOUT_AFTER_COMMIT")).andExpect(jsonPath("$.status").value("PENDING"));
        Score faulted = scores.compute(from);
        assertThat(faulted.score()).isLessThan(healthy.score());
        assertThat(byKey(faulted).get("timeout_rate").score()).isZero();                 // 1 of 4 submissions timed out
        assertThat(byKey(faulted).get("recovery_rate").score()).isZero();                // 0 of 1 PENDING recovered
        assertThat(byKey(faulted).get("pending_age").valueText()).contains("1 unresolved");

        // Recovery: reconciliation resolves it.
        reconcile();
        Score recovered = scores.compute(from);
        assertThat(recovered.score()).isGreaterThan(faulted.score());
        assertThat(recovered.score()).as("a timeout still happened, so it does not return to 100").isLessThan(100);
        assertThat(byKey(recovered).get("recovery_rate").score()).isEqualTo(100);
        assertThat(byKey(recovered).get("recovery_rate").valueText()).contains("1 by reconciliation");
        assertThat(byKey(recovered).get("pending_age").valueText()).contains("No unresolved");
    }

    @Test
    void duplicateSignalsAreCountedFromStoredDataAndMustNotDoublePost() throws Exception {
        Instant from = Instant.now();
        Party lab = adminParty();
        Party payee = party("sc2");
        String k = key();
        pay(lab, payee.vpa(), 1_000, k).andExpect(status().isCreated());
        pay(lab, payee.vpa(), 1_000, k).andExpect(status().isOk());      // idempotent replay
        pay(lab, payee.vpa(), 1_000, k).andExpect(status().isOk());      // idempotent replay
        pay(lab, payee.vpa(), 1_000, key(), scenario("DUPLICATE_CALLBACK", 5, 4));
        advanceBank(60);                                                  // 4 callbacks: 1 applied, 3 ignored

        Component dup = byKey(scores.compute(from)).get("duplicate_prevention");
        assertThat(dup.available()).isTrue();
        assertThat(dup.score()).isEqualTo(100);
        assertThat(dup.valueText()).contains("0 double/missing postings").contains("2 idempotent replays")
                .contains("3 duplicate/stale callbacks");
    }

    @Test
    void snapshotsAreStoredAndExposedThroughTheAdminApi() throws Exception {
        Party lab = adminParty();
        Party user = party("sc3");
        Party payee = party("sc3p");
        pay(lab, payee.vpa(), 100, key());

        mvc.perform(post("/api/admin/reliability/snapshots").header("Authorization", lab.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.score").isNumber());
        mvc.perform(get("/api/admin/reliability/history?limit=5").header("Authorization", lab.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].capturedAt").isNotEmpty());
        mvc.perform(get("/api/admin/reliability?windowMinutes=60").header("Authorization", lab.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.length()").value(7))
                .andExpect(jsonPath("$.methodology").isNotEmpty())
                .andExpect(jsonPath("$.disclaimer").value(org.hamcrest.Matchers.containsString("not an industry-standard benchmark")));

        mvc.perform(get("/api/admin/reliability").header("Authorization", user.bearer())).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/reliability/history").header("Authorization", user.bearer())).andExpect(status().isForbidden());
    }
}
