package com.paysure.collect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.paysure.collect.application.CollectService;
import com.paysure.support.PaymentTestBase;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class CollectIntegrationTest extends PaymentTestBase {

    @Autowired
    CollectService collects;

    @Test
    void requestApproveAndPay() throws Exception {
        Party merchant = party("shop");
        Party customer = party("cust");
        JsonNode req = body(request(merchant, customer.vpa(), 30_000).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.myRole").value("REQUESTER")));
        String id = req.get("id").asText();

        mvc.perform(get("/api/collect-requests?direction=incoming").header("Authorization", customer.bearer()))
                .andExpect(jsonPath("$[0].id").value(id))
                .andExpect(jsonPath("$[0].myRole").value("PAYER"));

        // The requester cannot approve their own request.
        mvc.perform(post("/api/collect-requests/{id}/approve", id).header("Authorization", merchant.bearer()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("NOT_THE_PAYER"));

        JsonNode approval = body(mvc.perform(post("/api/collect-requests/{id}/approve", id).header("Authorization", customer.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.request.status").value("APPROVED"))
                .andExpect(jsonPath("$.payment.status").value("SUCCESS")));
        String paymentId = approval.at("/payment/id").asText();
        assertThat(approval.at("/request/paymentId").asText()).isEqualTo(paymentId);
        assertThat(balance(customer.accountId())).isEqualTo(SEED - 30_000);
        assertThat(balance(merchant.accountId())).isEqualTo(SEED + 30_000);

        // Approving again is idempotent: same payment, no second debit.
        mvc.perform(post("/api/collect-requests/{id}/approve", id).header("Authorization", customer.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.payment.id").value(paymentId));
        assertThat(balance(customer.accountId())).isEqualTo(SEED - 30_000);
        // ...and it cannot be declined after approval.
        mvc.perform(post("/api/collect-requests/{id}/decline", id).header("Authorization", customer.bearer()))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("COLLECT_NOT_PENDING"));
        assertLedgerInvariants();
    }

    @Test
    void concurrentApprovalsCreateExactlyOnePayment() throws Exception {
        Party merchant = party("cshop");
        Party customer = party("ccust");
        UUID id = UUID.fromString(body(request(merchant, customer.vpa(), 70_000)).get("id").asText());

        List<UUID> paymentIds = runConcurrently(6, () -> collects.approve(customer.user().id(), false, id).payment().getId());

        Set<UUID> distinct = paymentIds.stream().collect(Collectors.toSet());
        assertThat(distinct).hasSize(1);
        Long rows = jdbc.queryForObject("select count(*) from payments where collect_request_id = ?", Long.class, id);
        assertThat(rows).isEqualTo(1);
        assertThat(balance(customer.accountId())).isEqualTo(SEED - 70_000);
        assertLedgerInvariants();
    }

    @Test
    void declineAndCancel() throws Exception {
        Party merchant = party("dshop");
        Party customer = party("dcust");
        String declined = body(request(merchant, customer.vpa(), 100)).get("id").asText();
        mvc.perform(post("/api/collect-requests/{id}/decline", declined).header("Authorization", customer.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DECLINED"));
        mvc.perform(post("/api/collect-requests/{id}/approve", declined).header("Authorization", customer.bearer()))
                .andExpect(status().isUnprocessableEntity());

        String cancelled = body(request(merchant, customer.vpa(), 100)).get("id").asText();
        mvc.perform(post("/api/collect-requests/{id}/cancel", cancelled).header("Authorization", customer.bearer()))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/collect-requests/{id}/cancel", cancelled).header("Authorization", merchant.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(balance(customer.accountId())).isEqualTo(SEED);

        // A stranger can neither see nor act on the request.
        Party stranger = party("dstr");
        mvc.perform(post("/api/collect-requests/{id}/approve", declined).header("Authorization", stranger.bearer()))
                .andExpect(status().isNotFound());
    }

    @Test
    void expiredRequestCannotBeApproved() throws Exception {
        Party merchant = party("eshop");
        Party customer = party("ecust");
        String id = body(request(merchant, customer.vpa(), 100)).get("id").asText();
        jdbc.update("update collect_requests set expires_at = now() - interval '1 minute' where id = ?::uuid", id);

        mvc.perform(post("/api/collect-requests/{id}/approve", id).header("Authorization", customer.bearer()))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("COLLECT_EXPIRED"));
        mvc.perform(get("/api/collect-requests?direction=outgoing").header("Authorization", merchant.bearer()))
                .andExpect(jsonPath("$[0].status").value("EXPIRED"));
        assertThat(balance(customer.accountId())).isEqualTo(SEED);
    }

    @Test
    void validation() throws Exception {
        Party merchant = party("vshop");
        Party customer = party("vcust");
        Party other = party("voth");
        // Requesting into someone else's VPA
        Map<String, Object> body = new HashMap<>(Map.of("requesterVpa", other.vpa(), "payerVpa", customer.vpa(), "amountPaise", 100));
        mvc.perform(post("/api/collect-requests").header("Authorization", merchant.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("REQUESTER_NOT_OWNED"));
        request(merchant, merchant.vpa(), 100).andExpect(status().isUnprocessableEntity());
        request(merchant, "ghost-" + uniqueHandle("g") + "@paysure", 100).andExpect(status().isNotFound());
        request(merchant, customer.vpa(), 0).andExpect(status().isBadRequest());
    }

    private ResultActions request(Party requester, String payerVpa, long amount) throws Exception {
        Map<String, Object> body = Map.of("requesterVpa", requester.vpa(), "payerVpa", payerVpa, "amountPaise", amount,
                "note", "Dinner share");
        return mvc.perform(post("/api/collect-requests").header("Authorization", requester.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }
}
