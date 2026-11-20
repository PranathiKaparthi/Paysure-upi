package com.paysure.reliability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.paysure.support.ReliabilityTestBase;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class LabIntegrationTest extends ReliabilityTestBase {

    private JsonNode start(Party lab, String scenario, Party payee) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("scenario", scenario, "payeeVpa", payee.vpa()));
        return body(mvc.perform(post("/api/admin/lab/runs").header("Authorization", lab.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isCreated()));
    }

    private JsonNode act(Party lab, String runId, String action, int repeat) throws Exception {
        return body(mvc.perform(post("/api/admin/lab/runs/{id}/actions", runId).header("Authorization", lab.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("action", action, "repeat", repeat))))
                .andExpect(status().isOk()));
    }

    @Test
    void catalogueIsServedFromTheDatabaseAndIsAdminOnly() throws Exception {
        Party lab = adminParty();
        Party user = party("labuser");
        JsonNode scenarios = body(mvc.perform(get("/api/admin/lab/scenarios").header("Authorization", lab.bearer()))
                .andExpect(status().isOk()));
        assertThat(scenarios.size()).isEqualTo(13);
        assertThat(scenarios.findValuesAsText("code")).contains("LOST_ACKNOWLEDGEMENT", "STATUS_API_OUTAGE", "DUPLICATE_SUBMISSIONS");
        assertThat(scenarios.get(0).get("whatHappens").asText()).isNotBlank();

        mvc.perform(get("/api/admin/lab/scenarios").header("Authorization", user.bearer())).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/lab/runs").header("Authorization", user.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"scenario\":\"HAPPY_PATH\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/lab/runs").header("Authorization", lab.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"scenario\":\"NOPE\"}")).andExpect(status().isNotFound());
    }

    @Test
    void lostAcknowledgement_pendingThenRecoveredByReconciliationWithOneLedgerPosting() throws Exception {
        Party lab = adminParty();
        Party payee = party("labp1");
        JsonNode run = start(lab, "LOST_ACKNOWLEDGEMENT", payee);
        String id = run.get("id").asText();

        // Before recovery: PENDING, funds held, nothing debited, no ledger posting.
        assertThat(run.at("/payment/status").asText()).isEqualTo("PENDING");
        assertThat(run.at("/statusAfterSubmit").asText()).isEqualTo("PENDING");
        assertThat(run.at("/afterSubmit/payerBalancePaise").asLong()).isEqualTo(SEED);
        assertThat(run.at("/afterSubmit/payerReservedPaise").asLong()).isEqualTo(50_000);
        assertThat(run.at("/ledger")).isEmpty();
        assertThat(run.at("/simulatedBank/committedStatus").asText()).isEqualTo("SUCCESS");
        assertThat(run.at("/simulatedBank/responseDelivered").asText()).isEqualTo("TIMEOUT");
        assertThat(run.at("/availableActions").findValuesAsText("")).isEmpty(); // array of strings, checked below
        assertThat(run.get("availableActions").toString()).contains("RECONCILE");

        JsonNode after = act(lab, id, "RECONCILE", 1);

        assertThat(after.at("/payment/status").asText()).isEqualTo("SUCCESS");
        assertThat(after.at("/payment/holdStatus").asText()).isEqualTo("CAPTURED");
        assertThat(after.at("/now/payerBalancePaise").asLong()).isEqualTo(SEED - 50_000);
        assertThat(after.at("/now/payerReservedPaise").asLong()).isZero();
        assertThat(after.at("/now/payeeBalancePaise").asLong()).isEqualTo(after.at("/before/payeeBalancePaise").asLong() + 50_000);
        assertThat(after.get("ledger")).hasSize(2);
        assertThat(after.at("/reconciliation/0/outcome").asText()).isEqualTo("RESOLVED");
        assertThat(after.at("/timeline").findValuesAsText("actor")).contains("CLIENT", "PAYSURE", "BANK", "RECONCILER");
        assertThat(after.at("/explanation").toString()).contains("never a new transfer").contains("exactly-once");
        assertThat(after.get("finished").asBoolean()).isTrue();
        assertThat(transferJournals(after.at("/payment/id").asText())).isEqualTo(1);
        assertThat(detail(lab, after.at("/payment/id").asText()).get("attempts")).hasSize(1);
        // The timeline is chronological and begins with the client's request, before any PaySure/bank event.
        assertThat(after.at("/timeline/0/actor").asText()).isEqualTo("CLIENT");
        List<java.time.Instant> times = after.get("timeline").findValuesAsText("at").stream().map(java.time.Instant::parse).toList();
        assertThat(times).isSorted();
        assertLedgerInvariants();
    }

    @Test
    void statusApiOutage_staysUnknownUntilTheApiIsRestored() throws Exception {
        Party lab = adminParty();
        Party payee = party("labp2");
        JsonNode run = start(lab, "STATUS_API_OUTAGE", payee);
        String id = run.get("id").asText();
        assertThat(run.get("statusApiUp").asBoolean()).isFalse();

        JsonNode unknown = act(lab, id, "RECONCILE", 1);
        assertThat(unknown.at("/payment/status").asText()).isEqualTo("PENDING");
        assertThat(unknown.at("/reconciliation/0/outcome").asText()).isEqualTo("UNKNOWN");
        assertThat(unknown.get("availableActions").toString()).contains("RESTORE_STATUS_API");
        assertThat(unknown.at("/explanation").toString()).contains("status API is down");
        assertThat(unknown.at("/now/payerReservedPaise").asLong()).isEqualTo(50_000);

        act(lab, id, "RESTORE_STATUS_API", 1);
        JsonNode healed = act(lab, id, "RECONCILE", 1);
        assertThat(healed.at("/payment/status").asText()).isEqualTo("SUCCESS");
        assertThat(healed.get("statusApiUp").asBoolean()).isTrue();
        assertThat(healed.at("/reconciliation").findValuesAsText("outcome")).containsExactly("UNKNOWN", "RESOLVED");
        assertLedgerInvariants();
    }

    @Test
    void duplicateSubmissions_tenRequestsOneLogicalPayment() throws Exception {
        Party lab = adminParty();
        Party payee = party("labp3");
        JsonNode run = start(lab, "DUPLICATE_SUBMISSIONS", payee);
        assertThat(run.get("requestsSent").asInt()).isEqualTo(10);
        assertThat(run.get("distinctPayments").asInt()).isEqualTo(1);
        assertThat(paymentsFrom(lab.accountId())).isEqualTo(1);
        assertThat(run.at("/payment/status").asText()).isEqualTo("SUCCESS");
        assertThat(run.get("ledger")).hasSize(2);
        assertThat(run.at("/explanation").toString()).contains("10 identical requests produced 1 logical payment");
        assertLedgerInvariants();
    }

    @Test
    void stuckAtBank_isEscalatedThenFinishesWhenTheBankDoes() throws Exception {
        Party lab = adminParty();
        Party payee = party("labp4");
        String id = start(lab, "STUCK_AT_BANK", payee).get("id").asText();

        JsonNode escalated = act(lab, id, "RECONCILE", 3); // escalation threshold is 3 in tests
        assertThat(escalated.at("/payment/status").asText()).isEqualTo("PENDING");
        assertThat(escalated.at("/payment/needsManualReview").asBoolean()).isTrue();
        assertThat(escalated.at("/reconciliation").findValuesAsText("outcome")).containsExactly("STILL_PENDING", "STILL_PENDING", "ESCALATED");
        assertThat(escalated.get("availableActions").toString()).contains("FAST_FORWARD");

        JsonNode done = act(lab, id, "FAST_FORWARD", 1);
        assertThat(done.at("/payment/status").asText()).isEqualTo("SUCCESS");
        assertThat(done.at("/ledger")).hasSize(2);
        assertLedgerInvariants();
    }

    @Test
    void definitiveFailuresAndDelayedConfirmationBehaveAsDescribed() throws Exception {
        Party lab = adminParty();
        Party payee = party("labp5");
        assertThat(start(lab, "HAPPY_PATH", payee).at("/payment/status").asText()).isEqualTo("SUCCESS");

        JsonNode decline = start(lab, "REMITTER_DECLINE", payee);
        assertThat(decline.at("/payment/status").asText()).isEqualTo("FAILED");
        assertThat(decline.at("/now/payerReservedPaise").asLong()).isZero();

        JsonNode down = start(lab, "BANK_DOWN", payee);
        assertThat(down.at("/payment/failureCode").asText()).isEqualTo("BANK_UNAVAILABLE");

        JsonNode delayed = start(lab, "DELAYED_CONFIRMATION", payee);
        assertThat(delayed.at("/payment/status").asText()).isEqualTo("PENDING");
        JsonNode ff = act(lab, delayed.get("id").asText(), "FAST_FORWARD", 1);
        assertThat(ff.at("/payment/status").asText()).isEqualTo("SUCCESS");

        JsonNode dups = start(lab, "DUPLICATE_CALLBACKS", payee);
        JsonNode dupsDone = act(lab, dups.get("id").asText(), "FAST_FORWARD", 1);
        assertThat(dupsDone.at("/payment/status").asText()).isEqualTo("SUCCESS");
        assertThat(dupsDone.at("/explanation").toString()).contains("duplicate or stale callback");
        assertThat(dupsDone.get("ledger")).hasSize(2);
        assertLedgerInvariants();

        mvc.perform(get("/api/admin/lab/runs").header("Authorization", lab.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].scenarioCode").value("DUPLICATE_CALLBACKS"));
    }
}
