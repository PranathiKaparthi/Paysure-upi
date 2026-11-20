package com.paysure.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.paysure.support.PaymentTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;

/** Mandatory cases 1–7 plus validation, authorization and visibility rules of POST /api/payments. */
class PaymentIntegrationTest extends PaymentTestBase {

    @Test
    void case1_successfulPayment() throws Exception {
        Party ananya = party("ananya");
        Party rahul = party("rahul");

        JsonNode p = body(pay(ananya, rahul.vpa(), 25_050, key())
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.holdStatus").value("CAPTURED"))
                .andExpect(jsonPath("$.direction").value("SENT"))
                .andExpect(jsonPath("$.amountPaise").value(25_050))
                .andExpect(jsonPath("$.bankReference").isNotEmpty()));

        assertThat(balance(ananya.accountId())).isEqualTo(SEED - 25_050);
        assertThat(balance(rahul.accountId())).isEqualTo(SEED + 25_050);
        assertThat(reserved(ananya.accountId())).isZero();
        assertThat(transferJournals(p.get("id").asText())).isEqualTo(1);

        JsonNode d = detail(ananya, p.get("id").asText());
        assertThat(d.at("/history").findValuesAsText("toStatus")).containsExactly("CREATED", "PROCESSING", "SUCCESS");
        assertThat(d.at("/simulatedBank/committedStatus").asText()).isEqualTo("SUCCESS");

        // The payee sees the same payment as RECEIVED; an unrelated user cannot see it at all.
        assertThat(detail(rahul, p.get("id").asText()).at("/payment/direction").asText()).isEqualTo("RECEIVED");
        Party stranger = party("stranger");
        mvc.perform(get("/api/payments/{id}", p.get("id").asText()).header("Authorization", stranger.bearer()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/payments").header("Authorization", rahul.bearer()))
                .andExpect(jsonPath("$[0].id").value(p.get("id").asText()));
        assertLedgerInvariants();
    }

    @Test
    void case2_insufficientBalanceFailsWithoutMovingMoney() throws Exception {
        Party payer = party("poor");
        Party payee = party("payee");

        JsonNode p = body(pay(payer, payee.vpa(), SEED + 1, key())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureCode").value("INSUFFICIENT_FUNDS"))
                .andExpect(jsonPath("$.holdStatus").value("NONE")));

        assertThat(balance(payer.accountId())).isEqualTo(SEED);
        assertThat(balance(payee.accountId())).isEqualTo(SEED);
        assertThat(transferJournals(p.get("id").asText())).isZero();
        // Never reached the bank.
        assertThat(detail(payer, p.get("id").asText()).get("attempts")).isEmpty();
        assertLedgerInvariants();
    }

    @Test
    void case3_invalidVpa() throws Exception {
        Party payer = party("vpa");
        pay(payer, "nobody-" + uniqueHandle("x") + "@paysure", 100, key())
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PAYEE_NOT_FOUND"));
        pay(payer, "not a vpa", 100, key())
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_VPA"));
        assertThat(paymentsFrom(payer.accountId())).isZero();
    }

    @Test
    void case4_invalidAmounts() throws Exception {
        Party payer = party("amt");
        Party payee = party("amtp");
        for (long bad : new long[] {0, -500, 10_000_001}) {
            pay(payer, payee.vpa(), bad, key()).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        // 250.5 paise must never be truncated to 250.
        String fractional = "{\"payerVpa\":\"" + payer.vpa() + "\",\"payeeVpa\":\"" + payee.vpa() + "\",\"amountPaise\":250.5}";
        mvc.perform(post("/api/payments").header("Authorization", payer.bearer()).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content(fractional))
                .andExpect(status().isBadRequest());
        String missing = "{\"payerVpa\":\"" + payer.vpa() + "\",\"payeeVpa\":\"" + payee.vpa() + "\"}";
        mvc.perform(post("/api/payments").header("Authorization", payer.bearer()).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content(missing))
                .andExpect(status().isBadRequest());
        assertThat(paymentsFrom(payer.accountId())).isZero();
    }

    @Test
    void case5_unauthorizedPayerAndSelfTransfer() throws Exception {
        Party ananya = party("ana");
        Party rahul = party("rah");
        // Ananya tries to pay FROM Rahul's VPA.
        Party impostor = new Party(ananya.user(), rahul.accountId(), rahul.vpa());
        pay(impostor, ananya.vpa(), 100, key())
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PAYER_NOT_OWNED"));
        assertThat(balance(rahul.accountId())).isEqualTo(SEED);

        pay(ananya, ananya.vpa(), 100, key())
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("SELF_TRANSFER_NOT_ALLOWED"));
        // Unauthenticated
        mvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void case6_duplicateIdempotencyKeyReturnsTheSamePayment() throws Exception {
        Party payer = party("dup");
        Party payee = party("dupp");
        String k = key();
        JsonNode first = body(pay(payer, payee.vpa(), 10_000, k).andExpect(status().isCreated()));
        JsonNode second = body(pay(payer, payee.vpa(), 10_000, k)
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true")));

        assertThat(second.get("id").asText()).isEqualTo(first.get("id").asText());
        assertThat(paymentsFrom(payer.accountId())).isEqualTo(1);
        assertThat(balance(payer.accountId())).isEqualTo(SEED - 10_000);
        assertThat(transferJournals(first.get("id").asText())).isEqualTo(1);
        assertLedgerInvariants();
    }

    @Test
    void case7_sameKeyWithDifferentPayloadIsAConflict() throws Exception {
        Party payer = party("conf");
        Party payee = party("confp");
        String k = key();
        pay(payer, payee.vpa(), 10_000, k).andExpect(status().isCreated());
        pay(payer, payee.vpa(), 99_999, k)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(paymentsFrom(payer.accountId())).isEqualTo(1);
        assertThat(balance(payer.accountId())).isEqualTo(SEED - 10_000);
    }

    @Test
    void keysAreScopedPerCaller() throws Exception {
        Party a = party("scopea");
        Party b = party("scopeb");
        Party payee = party("scopep");
        String shared = key();
        JsonNode pa = body(pay(a, payee.vpa(), 1_000, shared).andExpect(status().isCreated()));
        JsonNode pb = body(pay(b, payee.vpa(), 1_000, shared).andExpect(status().isCreated()));
        assertThat(pa.get("id").asText()).isNotEqualTo(pb.get("id").asText());
    }

    @Test
    void idempotencyKeyIsRequiredAndValidated() throws Exception {
        Party payer = party("nokey");
        Party payee = party("nokeyp");
        pay(payer, payee.vpa(), 100, null)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"));
        pay(payer, payee.vpa(), 100, "short")
                .andExpect(status().isBadRequest());
        pay(payer, payee.vpa(), 100, "has spaces in it!")
                .andExpect(status().isBadRequest());
        assertThat(paymentsFrom(payer.accountId())).isZero();
    }

    @Test
    void replayStillWorksAfterPayeeIsDeactivated() throws Exception {
        Party payer = party("replay");
        Party payee = party("replayp");
        String k = key();
        JsonNode first = body(pay(payer, payee.vpa(), 500, k).andExpect(status().isCreated()));
        mvc.perform(post("/api/accounts/{id}/deactivate", payee.accountId()).header("Authorization", payee.bearer()))
                .andExpect(status().isOk());
        pay(payer, payee.vpa(), 500, k).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(first.get("id").asText()));
        // But a NEW payment to the deactivated payee is refused.
        pay(payer, payee.vpa(), 500, key()).andExpect(status().isNotFound());
    }

    @Test
    void ordinaryUsersCannotInjectBankFaults() throws Exception {
        Party payer = party("nofault");
        Party payee = party("nofaultp");
        pay(payer, payee.vpa(), 100, key(), scenario("TIMEOUT_AFTER_COMMIT"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SIMULATION_NOT_ALLOWED"));
        assertThat(paymentsFrom(payer.accountId())).isZero();
    }

    @Test
    void historyIsAppendOnlyAndDatabaseRejectsInconsistentStates() throws Exception {
        Party payer = party("immut");
        Party payee = party("immutp");
        String id = body(pay(payer, payee.vpa(), 100, key())).get("id").asText();

        assertThatThrownBy(() -> jdbc.update("update payment_state_history set reason = 'x' where payment_id = ?::uuid", id))
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("delete from payment_state_history where payment_id = ?::uuid", id))
                .isInstanceOf(DataAccessException.class);
        // A SUCCESS payment whose hold is still HELD is impossible to persist.
        assertThatThrownBy(() -> jdbc.update("update payments set hold_status = 'HELD' where id = ?::uuid", id))
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("ck_payments_success_hold");
    }

    @Test
    void listFiltersByStatus() throws Exception {
        Party payer = party("list");
        Party payee = party("listp");
        pay(payer, payee.vpa(), 100, key());
        pay(payer, payee.vpa(), SEED * 5, key()); // fails: insufficient
        mvc.perform(get("/api/payments?status=FAILED").header("Authorization", payer.bearer()))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].failureCode").value("INSUFFICIENT_FUNDS"));
        mvc.perform(get("/api/payments").header("Authorization", payer.bearer()))
                .andExpect(jsonPath("$.length()").value(2));
    }
}
