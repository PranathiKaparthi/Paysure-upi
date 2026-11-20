package com.paysure.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.paysure.bank.BankContracts.Callback;
import com.paysure.bank.BankContracts.TransferStatus;
import com.paysure.payment.application.PaymentFinalizer;
import com.paysure.support.PaymentTestBase;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Mandatory cases 10–15: every simulated bank fault, asserting balances, holds and ledger postings —
 * not just HTTP codes. The payer is an ADMIN_DEMO user (only they may inject faults).
 */
class BankFaultScenarioTest extends PaymentTestBase {

    private static final long AMOUNT = 50_000; // ₹500

    @Autowired
    PaymentFinalizer finalizer;

    @Test
    void case10_bankTimeoutWithoutCommit_staysPendingThenVerifiesToFailedAndReleasesHold() throws Exception {
        Party lab = adminParty();
        Party payee = party("t10");
        String id = body(pay(lab, payee.vpa(), AMOUNT, key(), scenario("BANK_TIMEOUT"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.holdStatus").value("HELD"))).get("id").asText();

        // Ambiguous: funds are held (not spendable) but NOT debited, and nothing is assumed.
        assertThat(balance(lab.accountId())).isEqualTo(SEED);
        assertThat(reserved(lab.accountId())).isEqualTo(AMOUNT);
        assertLedgerInvariants();

        JsonNode check = checkStatus(lab, id);
        assertThat(check.get("effect").asText()).isEqualTo("SETTLED_FAILURE");
        assertThat(check.at("/payment/status").asText()).isEqualTo("FAILED");
        assertThat(check.at("/payment/holdStatus").asText()).isEqualTo("RELEASED");
        assertThat(balance(lab.accountId())).isEqualTo(SEED);
        assertThat(reserved(lab.accountId())).isZero();
        assertThat(balance(payee.accountId())).isEqualTo(SEED);
        assertLedgerInvariants();
    }

    @Test
    void case11_timeoutAfterSuccessfulCommit_isRecoveredBySuccessExactlyOnce() throws Exception {
        Party lab = adminParty();
        Party payee = party("t11");
        String id = body(pay(lab, payee.vpa(), AMOUNT, key(), scenario("TIMEOUT_AFTER_COMMIT"))
                .andExpect(jsonPath("$.status").value("PENDING"))).get("id").asText();

        // The bank actually committed, but PaySure was told "timeout": the two views differ.
        JsonNode d = detail(lab, id);
        assertThat(d.at("/simulatedBank/committedStatus").asText()).isEqualTo("SUCCESS");
        assertThat(d.at("/simulatedBank/responseDelivered").asText()).isEqualTo("TIMEOUT");
        assertThat(d.at("/attempts/0/responseKind").asText()).isEqualTo("TIMEOUT");
        assertThat(transferJournals(id)).isZero();

        assertThat(checkStatus(lab, id).get("effect").asText()).isEqualTo("SETTLED_SUCCESS");
        // Verifying again (or many times) never posts again.
        assertThat(checkStatus(lab, id).get("effect").asText()).isEqualTo("NO_CHANGE");
        assertThat(checkStatus(lab, id).at("/payment/status").asText()).isEqualTo("SUCCESS");

        assertThat(transferJournals(id)).isEqualTo(1);
        assertThat(balance(lab.accountId())).isEqualTo(SEED - AMOUNT);
        assertThat(balance(payee.accountId())).isEqualTo(SEED + AMOUNT);
        assertThat(reserved(lab.accountId())).isZero();
        // The transfer was submitted to the bank exactly once.
        assertThat(detail(lab, id).get("attempts")).hasSize(1);
        assertLedgerInvariants();
    }

    @Test
    void case12_bankUnavailable_failsImmediatelyAndReleasesHold() throws Exception {
        Party lab = adminParty();
        Party payee = party("t12");
        pay(lab, payee.vpa(), AMOUNT, key(), scenario("BANK_UNAVAILABLE"))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureCode").value("BANK_UNAVAILABLE"))
                .andExpect(jsonPath("$.holdStatus").value("RELEASED"));
        assertThat(balance(lab.accountId())).isEqualTo(SEED);
        assertThat(reserved(lab.accountId())).isZero();
        assertLedgerInvariants();
    }

    @Test
    void networkFailure_bankHasNoRecord_failsAfterGracePeriodWithoutDebit() throws Exception {
        Party lab = adminParty();
        Party payee = party("tnf");
        String id = body(pay(lab, payee.vpa(), AMOUNT, key(), scenario("NETWORK_FAILURE"))
                .andExpect(jsonPath("$.status").value("PENDING"))).get("id").asText();
        JsonNode check = checkStatus(lab, id);   // test grace period is 0s
        assertThat(check.at("/bankStatus/status").asText()).isEqualTo("NOT_FOUND");
        assertThat(check.at("/payment/failureCode").asText()).isEqualTo("BANK_NO_RECORD");
        assertThat(balance(lab.accountId())).isEqualTo(SEED);
        assertThat(reserved(lab.accountId())).isZero();
        assertLedgerInvariants();
    }

    @Test
    void case13_delayedSuccess_confirmedByCallbackAfterDelay() throws Exception {
        Party lab = adminParty();
        Party payee = party("t13");
        String id = body(pay(lab, payee.vpa(), AMOUNT, key(), scenario("DELAYED_SUCCESS", 30, 1))
                .andExpect(jsonPath("$.status").value("PENDING"))).get("id").asText();

        advanceBank(0);  // not due yet
        assertThat(detail(lab, id).at("/payment/status").asText()).isEqualTo("PENDING");
        assertThat(checkStatus(lab, id).at("/bankStatus/status").asText()).isEqualTo("PROCESSING");

        advanceBank(60); // bank resolves and delivers its callback
        JsonNode d = detail(lab, id);
        assertThat(d.at("/payment/status").asText()).isEqualTo("SUCCESS");
        assertThat(d.at("/history").findValuesAsText("source")).contains("BANK_CALLBACK");
        assertThat(balance(payee.accountId())).isEqualTo(SEED + AMOUNT);
        assertThat(transferJournals(id)).isEqualTo(1);
        assertLedgerInvariants();
    }

    @Test
    void delayedFailure_confirmedByCallback_releasesHold() throws Exception {
        Party lab = adminParty();
        Party payee = party("tdf");
        String id = body(pay(lab, payee.vpa(), AMOUNT, key(), scenario("DELAYED_FAILURE", 5, 1))).get("id").asText();
        assertThat(reserved(lab.accountId())).isEqualTo(AMOUNT);
        advanceBank(60);
        assertThat(detail(lab, id).at("/payment/status").asText()).isEqualTo("FAILED");
        assertThat(reserved(lab.accountId())).isZero();
        assertThat(balance(lab.accountId())).isEqualTo(SEED);
        assertLedgerInvariants();
    }

    @Test
    void case14_duplicateCallbacks_produceNoAdditionalLedgerPosting() throws Exception {
        Party lab = adminParty();
        Party payee = party("t14");
        String id = body(pay(lab, payee.vpa(), AMOUNT, key(), scenario("DUPLICATE_CALLBACK", 5, 4))).get("id").asText();

        advanceBank(60);
        JsonNode d = detail(lab, id);
        assertThat(d.at("/payment/status").asText()).isEqualTo("SUCCESS");
        assertThat(d.at("/attempts/0/callbacksReceived").asInt()).isEqualTo(1);
        assertThat(d.at("/attempts/0/callbacksIgnored").asInt()).isEqualTo(3);
        assertThat(d.at("/simulatedBank/callbacks").size()).isEqualTo(4);
        assertThat(transferJournals(id)).isEqualTo(1);
        assertThat(balance(lab.accountId())).isEqualTo(SEED - AMOUNT);
        assertThat(balance(payee.accountId())).isEqualTo(SEED + AMOUNT);
        assertLedgerInvariants();
    }

    @Test
    void case14b_concurrentRedeliveryOfTheSameCallback_postsOnce() throws Exception {
        Party lab = adminParty();
        Party payee = party("t14b");
        String id = body(pay(lab, payee.vpa(), AMOUNT, key(), scenario("DELAYED_SUCCESS", 600, 1))).get("id").asText();
        String ref = detail(lab, id).at("/payment/bankReference").asText();

        // Simulate an at-least-once transport redelivering the same SUCCESS callback 8 times at once.
        Callback cb = new Callback(UUID.randomUUID(), ref, 1, TransferStatus.SUCCESS, null);
        List<PaymentFinalizer.Effect> effects = runConcurrently(8, () -> finalizer.applyCallback(cb));

        assertThat(effects).filteredOn(e -> e == PaymentFinalizer.Effect.SETTLED_SUCCESS).hasSize(1);
        assertThat(effects).filteredOn(e -> e == PaymentFinalizer.Effect.IGNORED_STALE).hasSize(7);
        assertThat(transferJournals(id)).isEqualTo(1);
        assertThat(balance(payee.accountId())).isEqualTo(SEED + AMOUNT);
        assertLedgerInvariants();
    }

    @Test
    void case15_outOfOrderCallback_cannotRegressAFinalStatus() throws Exception {
        Party lab = adminParty();
        Party payee = party("t15");
        String id = body(pay(lab, payee.vpa(), AMOUNT, key(), scenario("OUT_OF_ORDER_CALLBACK", 5, 1))).get("id").asText();

        advanceBank(6);   // seq 2 (SUCCESS) arrives first
        assertThat(detail(lab, id).at("/payment/status").asText()).isEqualTo("SUCCESS");
        advanceBank(60);  // stale seq 1 (PROCESSING) arrives afterwards and is ignored
        JsonNode d = detail(lab, id);
        assertThat(d.at("/payment/status").asText()).isEqualTo("SUCCESS");
        assertThat(d.at("/attempts/0/callbacksIgnored").asInt()).isEqualTo(1);
        assertThat(d.at("/history").findValuesAsText("toStatus")).containsExactly("CREATED", "PROCESSING", "PENDING", "SUCCESS");
        assertThat(transferJournals(id)).isEqualTo(1);
        assertLedgerInvariants();
    }

    @Test
    void lateContradictingSignalIsReportedAsConflictAndChangesNothing() throws Exception {
        Party lab = adminParty();
        Party payee = party("tcf");
        String id = body(pay(lab, payee.vpa(), AMOUNT, key())).get("id").asText(); // NORMAL_SUCCESS
        String ref = detail(lab, id).at("/payment/bankReference").asText();

        PaymentFinalizer.Effect effect = finalizer.applyCallback(new Callback(UUID.randomUUID(), ref, 5, TransferStatus.FAILED, "late"));
        assertThat(effect).isEqualTo(PaymentFinalizer.Effect.CONFLICT);
        assertThat(detail(lab, id).at("/payment/status").asText()).isEqualTo("SUCCESS");
        assertThat(balance(payee.accountId())).isEqualTo(SEED + AMOUNT);
        assertLedgerInvariants();
    }

    @Test
    void callbackForUnknownReferenceIsIgnored() {
        assertThat(finalizer.applyCallback(new Callback(UUID.randomUUID(), "PSBDOESNOTEXIST", 1, TransferStatus.SUCCESS, null)))
                .isEqualTo(PaymentFinalizer.Effect.IGNORED_STALE);
    }

    @Test
    void heldFundsCannotBeSpentTwiceWhileOutcomeIsUnknown() throws Exception {
        Party lab = adminParty();
        Party payee = party("thold");
        // ₹9,000 is held by an ambiguous payment; only ₹1,000 remains available.
        pay(lab, payee.vpa(), 900_000, key(), scenario("TIMEOUT_AFTER_COMMIT")).andExpect(jsonPath("$.status").value("PENDING"));
        pay(lab, payee.vpa(), 200_000, key())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureCode").value("INSUFFICIENT_FUNDS"));
        assertLedgerInvariants();
    }
}
