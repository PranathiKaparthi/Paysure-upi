package com.paysure.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.paysure.support.PaymentTestBase;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/** ENFORCE mode with a block score of 55: the eighth rapid payment (score 55) is refused. */
@TestPropertySource(properties = {"paysure.risk.mode=ENFORCE", "paysure.risk.block-score=55"})
class RiskEnforceTest extends PaymentTestBase {

    @Test
    void aPaymentAtOrAboveTheBlockScoreIsRefusedWithoutCreatingAnythingAndTheEvidenceIsKept() throws Exception {
        Party payer = party("re1");
        Party payee = party("re1p");
        for (int i = 1; i <= 7; i++) {
            pay(payer, payee.vpa(), 100, key()).andExpect(status().isCreated());
        }
        String blockedKey = key();
        pay(payer, payee.vpa(), 100, blockedKey)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RISK_BLOCKED"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("8 transfers initiated in 60 seconds")))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Educational simulation")));

        // Nothing was created, nothing moved, and the idempotency key was NOT consumed (the claim rolled back).
        assertThat(paymentsFrom(payer.accountId())).isEqualTo(7);
        assertThat(balance(payer.accountId())).isEqualTo(SEED - 700);
        assertThat(jdbc.queryForObject("select count(*) from idempotency_records where idempotency_key = ?", Long.class, blockedKey)).isZero();
        assertLedgerInvariants();

        // ...but the refusal itself is recorded (it survives the rollback) and published.
        JsonNode alerts = readJson(mvc.perform(get("/api/risk/alerts").header("Authorization", payer.bearer()))
                .andReturn().getResponse().getContentAsString());
        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).get("blocked").asBoolean()).isTrue();
        assertThat(alerts.get(0).has("paymentId")).isFalse();
        assertThat(alerts.get(0).get("score").asInt()).isEqualTo(55);
        Map<String, Object> event = jdbc.queryForMap("select * from outbox_events where aggregate_id = ?::uuid",
                alerts.get(0).get("id").asText());
        assertThat(readJson((String) event.get("payload")).at("/data/blocked").asBoolean()).isTrue();
    }

    @Test
    void belowTheBlockScoreEnforcementChangesNothingAndTheAssessmentSaysWhatWouldHappen() throws Exception {
        Party payer = party("re2");
        Party payee = party("re2p");
        pay(payer, payee.vpa(), 500_000, key()).andExpect(status().isCreated());   // score 40: alert only
        mvc.perform(post("/api/risk/assess").header("Authorization", payer.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("payerVpa", payer.vpa(), "payeeVpa", payee.vpa(), "amountPaise", 100))))
                .andExpect(jsonPath("$.mode").value("ENFORCE")).andExpect(jsonPath("$.wouldBlock").value(false));
        assertThat(balance(payee.accountId())).isEqualTo(SEED + 500_000);
    }

    @Test
    void theRetryAfterTheWindowPassesWouldSucceedBecauseNoStateWasLeftBehind() throws Exception {
        Party payer = party("re3");
        Party payee = party("re3p");
        for (int i = 1; i <= 7; i++) {
            pay(payer, payee.vpa(), 100, key()).andExpect(status().isCreated());
        }
        String k = key();
        pay(payer, payee.vpa(), 100, k).andExpect(status().isUnprocessableEntity());
        // Simulate the velocity window passing by ageing the earlier payments.
        jdbc.update("update payments set created_at = created_at - interval '5 minutes' where payer_account_id = ?", payer.accountId());
        pay(payer, payee.vpa(), 100, k).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("SUCCESS"));
    }
}
