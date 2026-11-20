package com.paysure.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.paysure.notification.NotificationEventHandler;
import com.paysure.notification.WebhookEndpointService;
import com.paysure.support.EventsTestBase;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;

/** Default ALERT mode: risk is recorded, explained and published, and never changes the outcome of a payment. */
class RiskIntegrationTest extends EventsTestBase {

    @Autowired
    NotificationEventHandler handler;
    @Autowired
    WebhookEndpointService endpoints;

    private JsonNode alertsOf(Party who) throws Exception {
        return readJson(mvc.perform(get("/api/risk/alerts").header("Authorization", who.bearer())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    @Test
    void highVelocityAndRapidRepeatsProduceAnExplainedAlertWithoutBlockingAnything() throws Exception {
        Party payer = party("rk1");
        Party payee = party("rk1p");
        String last = null;
        for (int i = 1; i <= 8; i++) {
            JsonNode p = body(pay(payer, payee.vpa(), 100, key()).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("SUCCESS")));
            last = p.get("id").asText();
            if (i < 8) {
                assertThat(alertsOf(payer)).as("below the alert score after %d payments", i).isEmpty();
            }
        }
        JsonNode alerts = alertsOf(payer);
        assertThat(alerts).hasSize(1);
        JsonNode a = alerts.get(0);
        assertThat(a.get("score").asInt()).isEqualTo(55);
        assertThat(a.get("level").asText()).isEqualTo("MEDIUM");
        assertThat(a.get("action").asText()).isEqualTo("REVIEW");
        assertThat(a.get("blocked").asBoolean()).isFalse();
        assertThat(a.get("status").asText()).isEqualTo("OPEN");
        assertThat(a.get("paymentId").asText()).isEqualTo(last);
        assertThat(a.get("rules").findValuesAsText("code")).containsExactly("HIGH_VELOCITY", "RAPID_REPEAT");
        assertThat(a.get("explanation").get(0).asText()).isEqualTo("Risk score: 55/100 (medium).");
        assertThat(a.get("explanation").toString()).contains("8 transfers initiated in 60 seconds (threshold 8). (+35)");
        assertThat(a.get("suggestedAction").asText()).startsWith("Allow but flag");

        // The payments themselves were untouched.
        assertThat(balance(payer.accountId())).isEqualTo(SEED - 800);
        assertThat(balance(payee.accountId())).isEqualTo(SEED + 800);
        assertLedgerInvariants();

        // The alert is attached to the payment for the transaction page.
        mvc.perform(get("/api/risk/alerts?paymentId=" + last).header("Authorization", payer.bearer()))
                .andExpect(jsonPath("$.length()").value(1));
        assertThat(alertsOf(payee)).as("the recipient is not the subject of the alert").isEmpty();
    }

    @Test
    void aLargeTransferToANewRecipientIsFlaggedButStillSucceeds() throws Exception {
        Party payer = party("rk2");
        Party payee = party("rk2p");
        JsonNode p = body(pay(payer, payee.vpa(), 500_000, key()).andExpect(status().isCreated()));
        assertThat(p.get("status").asText()).isEqualTo("SUCCESS");

        JsonNode a = alertsOf(payer).get(0);
        assertThat(a.get("score").asInt()).isEqualTo(40);
        assertThat(a.get("level").asText()).isEqualTo("MEDIUM");
        assertThat(a.get("rules").findValuesAsText("code")).containsExactly("LARGE_AMOUNT", "NEW_RECIPIENT");
        assertThat(a.get("explanation").toString()).contains("₹5,000.00 is at or above the demo threshold");

        // Paying the same (now known) recipient again is no longer "new".
        pay(payer, payee.vpa(), 500_000, key()).andExpect(status().isCreated());
        assertThat(alertsOf(payer)).as("LARGE_AMOUNT alone is 25 points: below the alert score").hasSize(1);
    }

    @Test
    void repeatedFailuresRaiseTheScoreOfTheNextPayment() throws Exception {
        Party payer = party("rk3");
        Party payee = party("rk3p");
        for (int i = 0; i < 3; i++) {
            pay(payer, payee.vpa(), SEED + 1, key()).andExpect(jsonPath("$.status").value("FAILED"));
        }
        pay(payer, payee.vpa(), SEED + 1, key()).andExpect(jsonPath("$.status").value("FAILED"));

        JsonNode newest = alertsOf(payer).get(0);   // newest first
        assertThat(newest.get("rules").findValuesAsText("code")).contains("MULTIPLE_FAILURES", "NEW_RECIPIENT_BURST", "LARGE_AMOUNT");
        assertThat(newest.get("explanation").toString()).contains("3 payments failed in the last 10 minutes");
        assertThat(balance(payer.accountId())).as("failed payments moved nothing").isEqualTo(SEED);
        assertLedgerInvariants();
    }

    @Test
    void tenConcurrentIdenticalRequestsAreEvaluatedAndAlertedExactlyOnce() throws Exception {
        Party payer = party("rk4");
        Party payee = party("rk4p");
        String sharedKey = key();
        runConcurrently(10, () -> pay(payer, payee.vpa(), 500_000, sharedKey).andReturn().getResponse().getStatus());

        assertThat(paymentsFrom(payer.accountId())).isEqualTo(1);
        assertThat(alertsOf(payer)).as("duplicates replay the first payment and are not re-evaluated").hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from outbox_events where event_type = 'risk.alert' and payload like ?",
                Long.class, "%" + payer.user().id() + "%")).isEqualTo(1);
    }

    @Test
    void theAlertEventCommitsWithThePaymentAndReachesTheAffectedUsersWebhookOnly() throws Exception {
        Party payer = party("rk5");
        Party payee = party("rk5p");
        Party stranger = party("rk5s");
        UUID payerEp = endpoints.create(payer.user().id(), "http://localhost:9/hook", List.of("risk.alert"), "risk").endpoint().id();
        UUID strangerEp = endpoints.create(stranger.user().id(), "http://localhost:9/hook", List.of("risk.alert"), "risk").endpoint().id();

        pay(payer, payee.vpa(), 500_000, key()).andExpect(status().isCreated());
        String alertId = alertsOf(payer).get(0).get("id").asText();

        Map<String, Object> row = jdbc.queryForMap("select * from outbox_events where aggregate_id = ?::uuid", alertId);
        assertThat(row.get("event_type")).isEqualTo("risk.alert");
        assertThat(row.get("topic")).isEqualTo("paysure.risk.alert");
        JsonNode data = envelope(row).get("data");
        assertThat(data.get("userId").asText()).isEqualTo(payer.user().id().toString());
        assertThat(data.get("score").asInt()).isEqualTo(40);
        assertThat(data.get("blocked").asBoolean()).isFalse();
        assertThat(data.get("rules").findValuesAsText("")).isEmpty();
        assertThat(data.get("rules").toString()).contains("LARGE_AMOUNT", "NEW_RECIPIENT");

        handler.handle((String) row.get("payload"));
        assertThat(jdbc.queryForObject("select count(*) from webhook_deliveries where endpoint_id = ?", Long.class, payerEp)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from webhook_deliveries where endpoint_id = ?", Long.class, strangerEp)).isZero();
    }

    @Test
    void whatIfAssessmentExplainsWithoutRecordingAnything() throws Exception {
        Party payer = party("rk6");
        Party payee = party("rk6p");
        Party other = party("rk6o");
        Map<String, Object> req = Map.of("payerVpa", payer.vpa(), "payeeVpa", payee.vpa(), "amountPaise", 500_000);

        mvc.perform(post("/api/risk/assess").header("Authorization", payer.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.score").value(40)).andExpect(jsonPath("$.level").value("MEDIUM"))
                .andExpect(jsonPath("$.wouldAlert").value(true)).andExpect(jsonPath("$.wouldBlock").value(false))
                .andExpect(jsonPath("$.mode").value("ALERT"))
                .andExpect(jsonPath("$.rules[0].code").value("LARGE_AMOUNT"))
                .andExpect(jsonPath("$.disclaimer").value(org.hamcrest.Matchers.containsString("not real fraud determinations")));
        assertThat(alertsOf(payer)).isEmpty();
        assertThat(paymentsFrom(payer.accountId())).isZero();

        // Only your own payer VPA, a real payee, a sane amount.
        mvc.perform(post("/api/risk/assess").header("Authorization", other.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req))).andExpect(status().isForbidden());
        Map<String, Object> ghost = new HashMap<>(req);
        ghost.put("payeeVpa", "nobody-" + uniqueHandle("g") + "@paysure");
        mvc.perform(post("/api/risk/assess").header("Authorization", payer.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(ghost))).andExpect(status().isNotFound());
        Map<String, Object> zero = new HashMap<>(req);
        zero.put("amountPaise", 0);
        mvc.perform(post("/api/risk/assess").header("Authorization", payer.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(zero))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/risk/assess").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(req)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void alertVisibilityAndReviewAreScopedAndAdminOnly() throws Exception {
        Party payer = party("rk7");
        Party payee = party("rk7p");
        Party admin = adminParty();
        pay(payer, payee.vpa(), 500_000, key());
        String id = alertsOf(payer).get(0).get("id").asText();

        mvc.perform(get("/api/admin/risk/alerts?status=OPEN").header("Authorization", payer.bearer())).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/risk/alerts/{id}/review", id).header("Authorization", payer.bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DISMISSED\"}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/risk/alerts?status=OPEN").header("Authorization", admin.bearer())).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + id + "')]").isNotEmpty());

        mvc.perform(post("/api/admin/risk/alerts/{id}/review", id).header("Authorization", admin.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"BOGUS\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/risk/alerts/{id}/review", id).header("Authorization", admin.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACKNOWLEDGED\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACKNOWLEDGED")).andExpect(jsonPath("$.reviewedAt").isNotEmpty());
        mvc.perform(post("/api/admin/risk/alerts/{id}/review", id).header("Authorization", admin.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DISMISSED\"}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("ALERT_ALREADY_REVIEWED"));
        mvc.perform(post("/api/admin/risk/alerts/{id}/review", UUID.randomUUID()).header("Authorization", admin.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DISMISSED\"}")).andExpect(status().isNotFound());

        mvc.perform(get("/api/risk/alerts?status=ACKNOWLEDGED").header("Authorization", payer.bearer()))
                .andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/risk/alerts?status=OPEN").header("Authorization", payer.bearer())).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void configExposesTheActiveRulesAndTheDisclaimer() throws Exception {
        Party user = party("rk8");
        mvc.perform(get("/api/risk/config").header("Authorization", user.bearer())).andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("ALERT")).andExpect(jsonPath("$.alertScore").value(40))
                .andExpect(jsonPath("$.rules.length()").value(6))
                .andExpect(jsonPath("$.rules[?(@.code=='LARGE_AMOUNT')].thresholdPaise").value(500_000))
                .andExpect(jsonPath("$.disclaimer").value(org.hamcrest.Matchers.containsString("no machine-learning model")));
        mvc.perform(get("/api/risk/config")).andExpect(status().isUnauthorized());
    }

    @Test
    void theDatabaseRefusesInconsistentAlerts() throws Exception {
        Party payer = party("rk9");
        Party payee = party("rk9p");
        String paymentId = body(pay(payer, payee.vpa(), 500_000, key())).get("id").asText();
        // A blocked attempt can never reference a payment.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("""
                insert into risk_alerts (id, user_id, payment_id, payer_vpa, payee_vpa, amount_paise, score, level, action, blocked,
                                         rules, explanation, suggested_action)
                values (?, ?, ?::uuid, 'a@paysure', 'b@paysure', 1, 50, 'MEDIUM', 'REVIEW', true, '[]', 'x', 'y')""",
                UUID.randomUUID(), payer.user().id(), paymentId)).isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("ck_risk_alerts_blocked");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("""
                insert into risk_alerts (id, user_id, payer_vpa, payee_vpa, amount_paise, score, level, action, rules, explanation, suggested_action)
                values (?, ?, 'a@paysure', 'b@paysure', 1, 101, 'HIGH', 'REVIEW', '[]', 'x', 'y')""",
                UUID.randomUUID(), payer.user().id())).isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("ck_risk_alerts_score");
    }
}
