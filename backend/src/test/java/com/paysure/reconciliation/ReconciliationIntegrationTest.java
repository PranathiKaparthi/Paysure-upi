package com.paysure.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.paysure.reconciliation.ReconciliationModel.RunSummary;
import com.paysure.reconciliation.ReconciliationModel.TriggerType;
import com.paysure.support.ReliabilityTestBase;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Mandatory cases 16–18 plus escalation, conflicts, leases and ledger checks of the reconciliation worker. */
class ReconciliationIntegrationTest extends ReliabilityTestBase {

    private static final long AMOUNT = 50_000;

    private String lostAck(Party lab, Party payee) throws Exception {
        return body(pay(lab, payee.vpa(), AMOUNT, key(), scenario("TIMEOUT_AFTER_COMMIT"))
                .andExpect(jsonPath("$.status").value("PENDING"))).get("id").asText();
    }

    @Test
    void case16_reconciliationFindsTheCommittedOutcomeAndFinalisesOnceWithoutResending() throws Exception {
        Party lab = adminParty();
        Party payee = party("r16");
        String id = lostAck(lab, payee);
        assertThat(balance(lab.accountId())).isEqualTo(SEED);
        assertThat(reserved(lab.accountId())).isEqualTo(AMOUNT);

        RunSummary run = reconcile();

        assertThat(run.status()).isEqualTo("COMPLETED");
        assertThat(run.resolvedSuccess()).isGreaterThanOrEqualTo(1);
        assertThat(paymentStatus(id)).isEqualTo("SUCCESS");
        assertThat(outcomes(id)).containsExactly("RESOLVED");
        assertThat(items(id).get(0).get("recovery_ms")).isNotNull();
        assertThat(transferJournals(id)).isEqualTo(1);
        assertThat(balance(lab.accountId())).isEqualTo(SEED - AMOUNT);
        assertThat(balance(payee.accountId())).isEqualTo(SEED + AMOUNT);
        assertThat(reserved(lab.accountId())).isZero();
        // The transfer was never re-sent: still exactly one bank submission.
        assertThat(detail(lab, id).get("attempts")).hasSize(1);
        assertThat(detail(lab, id).at("/history").findValuesAsText("source")).contains("RECONCILIATION");

        // A second run finds nothing left to do: it is no longer in flight, and the bank itself just confirmed it.
        reconcile();
        assertThat(items(id)).hasSize(1);
        assertThat(transferJournals(id)).isEqualTo(1);
        assertLedgerInvariants();
    }

    @Test
    void normallySettledPaymentsAreCrossCheckedAgainstTheBankExactlyOnce() throws Exception {
        Party lab = adminParty();
        Party payee = party("r16d");
        String id = body(pay(lab, payee.vpa(), AMOUNT, key())).get("id").asText();
        reconcile();
        reconcile();
        assertThat(items(id)).hasSize(1);
        assertThat(items(id).get(0).get("check_kind")).isEqualTo("POST_SETTLEMENT");
        assertThat(items(id).get(0).get("outcome")).isEqualTo("CONSISTENT");
        assertThat(paymentStatus(id)).isEqualTo("SUCCESS");
    }

    @Test
    void timeoutThatNeverCommittedIsResolvedToFailedAndTheHoldReleased() throws Exception {
        Party lab = adminParty();
        Party payee = party("r16b");
        String id = body(pay(lab, payee.vpa(), AMOUNT, key(), scenario("BANK_TIMEOUT"))).get("id").asText();
        reconcile();
        assertThat(paymentStatus(id)).isEqualTo("FAILED");
        assertThat(balance(lab.accountId())).isEqualTo(SEED);
        assertThat(reserved(lab.accountId())).isZero();
        assertThat(transferJournals(id)).isZero();
        assertLedgerInvariants();
    }

    @Test
    void networkFailureWithNoBankRecordIsFailedAfterTheGracePeriod() throws Exception {
        Party lab = adminParty();
        Party payee = party("r16c");
        String id = body(pay(lab, payee.vpa(), AMOUNT, key(), scenario("NETWORK_FAILURE"))).get("id").asText();
        reconcile();
        assertThat(paymentStatus(id)).isEqualTo("FAILED");
        assertThat(items(id).get(0).get("bank_status")).isEqualTo("NOT_FOUND");
        assertThat(reserved(lab.accountId())).isZero();
        assertLedgerInvariants();
    }

    @Test
    void case17_unknownOutcomeStaysPendingAndIsNeverGuessed() throws Exception {
        Party lab = adminParty();
        Party payee = party("r17");
        String id = lostAck(lab, payee);
        bank.setStatusApiAvailable(false);

        reconcile();

        assertThat(paymentStatus(id)).isEqualTo("PENDING");
        assertThat(outcomes(id)).containsExactly("UNKNOWN");
        assertThat(reserved(lab.accountId())).isEqualTo(AMOUNT);   // hold untouched
        assertThat(balance(lab.accountId())).isEqualTo(SEED);      // nothing debited, nothing assumed
        assertThat(transferJournals(id)).isZero();
        assertThat(detail(lab, id).get("attempts")).hasSize(1);    // never re-sent

        bank.setStatusApiAvailable(true);
        reconcile();
        assertThat(paymentStatus(id)).isEqualTo("SUCCESS");
        assertThat(outcomes(id)).containsExactly("UNKNOWN", "RESOLVED");
        assertThat(transferJournals(id)).isEqualTo(1);
        assertLedgerInvariants();
    }

    @Test
    void stillProcessingAtTheBankIsLeftPendingThenEscalatedToManualReview() throws Exception {
        Party lab = adminParty();
        Party payee = party("r17b");
        String id = body(pay(lab, payee.vpa(), AMOUNT, key(), scenario("DELAYED_SUCCESS", 600, 1))).get("id").asText();

        reconcile();
        reconcile();
        assertThat(outcomes(id)).containsExactly("STILL_PENDING", "STILL_PENDING");
        assertThat(jdbc.queryForObject("select needs_manual_review from payments where id = ?::uuid", Boolean.class, id)).isFalse();

        reconcile(); // third unresolved check (threshold is 3 in tests)
        assertThat(outcomes(id)).containsExactly("STILL_PENDING", "STILL_PENDING", "ESCALATED");
        assertThat(paymentStatus(id)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select manual_review_reason from payments where id = ?::uuid", String.class, id))
                .contains("3 reconciliation checks");
        mvc.perform(get("/api/admin/reconciliation/manual-review").header("Authorization", lab.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$[?(@.paymentId=='" + id + "')].reason").isNotEmpty());

        // It keeps being retried; once the bank finishes, it resolves normally (and is only escalated once).
        advanceBank(700);
        assertThat(paymentStatus(id)).isEqualTo("SUCCESS");
        assertThat(outcomes(id).stream().filter("ESCALATED"::equals).count()).isEqualTo(1);
        assertLedgerInvariants();
    }

    @Test
    void case18_duplicateReconciliationWorkersNeverActOnTheSamePayment() throws Exception {
        Party lab = adminParty();
        Party payee = party("r18");
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            ids.add(lostAck(lab, payee));
        }

        // Four workers started at the same instant.
        List<RunSummary> runs = runConcurrently(4, () -> worker.run(TriggerType.MANUAL, Duration.ZERO));

        assertThat(runs).allSatisfy(r -> assertThat(r.status()).isEqualTo("COMPLETED"));
        for (String id : ids) {
            assertThat(paymentStatus(id)).isEqualTo("SUCCESS");
            long inFlightChecks = items(id).stream().filter(i -> "IN_FLIGHT".equals(i.get("check_kind"))).count();
            assertThat(inFlightChecks).as("each payment is claimed by exactly one worker").isEqualTo(1);
            assertThat(outcomes(id).stream().filter("RESOLVED"::equals).count()).isEqualTo(1);
            assertThat(transferJournals(id)).isEqualTo(1);
        }
        assertThat(balance(lab.accountId())).isEqualTo(SEED - 6 * AMOUNT);
        assertThat(balance(payee.accountId())).isEqualTo(SEED + 6 * AMOUNT);
        assertLedgerInvariants();
    }

    @Test
    void aLiveLeaseKeepsOtherWorkersAwayAndAnExpiredLeaseIsReclaimed() throws Exception {
        Party lab = adminParty();
        Party payee = party("r18b");
        String id = lostAck(lab, payee);

        // Simulate another worker that is mid-way through this payment.
        jdbc.update("update payments set recon_lease_owner = ?, recon_lease_until = now() + interval '5 minutes' where id = ?::uuid",
                UUID.randomUUID(), id);
        reconcile();
        assertThat(paymentStatus(id)).isEqualTo("PENDING");
        assertThat(items(id)).isEmpty();

        // That worker crashed: its lease expires and the payment is picked up.
        jdbc.update("update payments set recon_lease_until = now() - interval '1 minute' where id = ?::uuid", id);
        reconcile();
        assertThat(paymentStatus(id)).isEqualTo("SUCCESS");
        assertThat(jdbc.queryForObject("select recon_lease_owner from payments where id = ?::uuid", UUID.class, id)).isNull();
    }

    @Test
    void freshPaymentsYoungerThanTheMinimumAgeAreLeftToTheNormalFlow() throws Exception {
        Party lab = adminParty();
        Party payee = party("r18c");
        String id = lostAck(lab, payee);
        worker.run(TriggerType.MANUAL, Duration.ofHours(1));
        assertThat(items(id)).isEmpty();
        assertThat(paymentStatus(id)).isEqualTo("PENDING");
    }

    @Test
    void conflictingRecordsAreFlaggedForReviewAndNeverAutoCorrected() throws Exception {
        Party lab = adminParty();
        Party payee = party("r19");
        String id = body(pay(lab, payee.vpa(), AMOUNT, key())).get("id").asText(); // SUCCESS locally
        String ref = detail(lab, id).at("/payment/bankReference").asText();
        // The bank's record now disagrees with PaySure's.
        jdbc.update("update sim_bank_transfers set committed_status = 'FAILED' where bank_reference = ?", ref);

        RunSummary run = reconcile();

        assertThat(run.conflicts()).isGreaterThanOrEqualTo(1);
        assertThat(outcomes(id)).containsExactly("CONFLICT");
        assertThat(paymentStatus(id)).isEqualTo("SUCCESS");                   // nothing "fixed" automatically
        assertThat(transferJournals(id)).isEqualTo(1);
        assertThat(balance(payee.accountId())).isEqualTo(SEED + AMOUNT);
        assertThat(jdbc.queryForObject("select needs_manual_review from payments where id = ?::uuid", Boolean.class, id)).isTrue();
        // Restore the bank's record so the shared database stays consistent for other tests.
        jdbc.update("update sim_bank_transfers set committed_status = 'SUCCESS' where bank_reference = ?", ref);
    }

    @Test
    void ledgerInconsistenciesAreDetectedAndReported() throws Exception {
        Party lab = adminParty();
        Party other = party("r20");
        jdbc.update("update accounts set balance_paise = balance_paise + 7 where id = ?", other.accountId());
        try {
            RunSummary run = reconcile();
            assertThat(run.ledgerInconsistencies()).isGreaterThanOrEqualTo(1);
            List<String> mismatches = jdbc.queryForList(
                    "select detail from reconciliation_items where run_id = ? and outcome = 'LEDGER_MISMATCH'", String.class, run.id());
            assertThat(mismatches).anyMatch(d -> d.contains("differs from ledger movement"));
            mvc.perform(get("/api/admin/reconciliation/report").header("Authorization", lab.bearer()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.ledgerInconsistencies").value(run.ledgerInconsistencies()));
        } finally {
            jdbc.update("update accounts set balance_paise = balance_paise - 7 where id = ?", other.accountId());
        }
        assertThat(reconcile().ledgerInconsistencies()).isZero();
    }

    @Test
    void reportAndRunApisAreAdminOnly() throws Exception {
        Party lab = adminParty();
        Party user = party("r21");
        Party payee = party("r21p");
        String id = lostAck(lab, payee);

        mvc.perform(post("/api/admin/reconciliation/run?minAgeSeconds=0").header("Authorization", lab.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.checked").isNumber());
        assertThat(paymentStatus(id)).isEqualTo("SUCCESS");

        mvc.perform(get("/api/admin/reconciliation/report?hours=1").header("Authorization", lab.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runs").isNumber()).andExpect(jsonPath("$.resolved").isNumber())
                .andExpect(jsonPath("$.stillPendingNow").isNumber()).andExpect(jsonPath("$.conflictingPayments").isNumber())
                .andExpect(jsonPath("$.ledgerInconsistencies").isNumber()).andExpect(jsonPath("$.manualReviewCases").isNumber())
                .andExpect(jsonPath("$.avgRecoveryMs").isNumber());
        mvc.perform(get("/api/admin/reconciliation/runs?limit=3").header("Authorization", lab.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").isNotEmpty());

        for (String path : List.of("/api/admin/reconciliation/report", "/api/admin/reconciliation/runs",
                "/api/admin/reconciliation/manual-review")) {
            mvc.perform(get(path).header("Authorization", user.bearer())).andExpect(status().isForbidden());
        }
        mvc.perform(post("/api/admin/reconciliation/run").header("Authorization", user.bearer())).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/reconciliation/report")).andExpect(status().isUnauthorized());
    }
}
