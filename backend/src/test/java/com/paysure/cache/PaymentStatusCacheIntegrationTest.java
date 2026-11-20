package com.paysure.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.paysure.support.ReliabilityTestBase;
import com.paysure.support.TestRedis;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/** Read-through payment-status cache on a real Redis: hits, post-commit invalidation, authorisation, TTLs, resilience. */
@TestPropertySource(properties = "paysure.cache.payment-status-enabled=true")
class PaymentStatusCacheIntegrationTest extends ReliabilityTestBase {

    @Autowired
    StringRedisTemplate redis;

    @DynamicPropertySource
    static void redisProps(DynamicPropertyRegistry registry) {
        TestRedis.Connection r = TestRedis.get();
        registry.add("spring.data.redis.host", r::host);
        registry.add("spring.data.redis.port", r::port);
    }

    private String key(String paymentId) {
        return "ps:payment-status:" + paymentId;
    }

    private org.springframework.test.web.servlet.ResultActions paymentStatusOf(Party who, String id) throws Exception {
        return mvc.perform(get("/api/payments/{id}/status", id).header("Authorization", who.bearer()));
    }

    @Test
    void firstReadMissesThenHitsAndTheCachedCopyMatchesTheDatabase() throws Exception {
        Party payer = party("ca1");
        Party payee = party("ca1p");
        String id = body(pay(payer, payee.vpa(), 5_000, key())).get("id").asText();
        redis.delete(key(id)); // creation already invalidated it; make the state explicit

        paymentStatusOf(payer, id).andExpect(status().isOk()).andExpect(header().string("X-Cache", "MISS"))
                .andExpect(jsonPath("$.status").value("SUCCESS")).andExpect(jsonPath("$.holdStatus").value("CAPTURED"))
                .andExpect(jsonPath("$.amountPaise").value(5_000));
        assertThat(redis.hasKey(key(id))).isTrue();
        paymentStatusOf(payer, id).andExpect(status().isOk()).andExpect(header().string("X-Cache", "HIT"))
                .andExpect(jsonPath("$.status").value("SUCCESS"));
        // Final states never change, so they get the longer TTL.
        assertThat(redis.getExpire(key(id))).isBetween(31L, 300L);
    }

    @Test
    void aCommittedStatusChangeInvalidatesTheEntryAfterCommitSoReadersSeeTheNewStatus() throws Exception {
        Party lab = adminParty();
        Party payee = party("ca2p");
        String id = body(pay(lab, payee.vpa(), 50_000, key(), scenario("TIMEOUT_AFTER_COMMIT"))).get("id").asText();

        paymentStatusOf(lab, id).andExpect(jsonPath("$.status").value("PENDING")).andExpect(header().string("X-Cache", "MISS"));
        paymentStatusOf(lab, id).andExpect(jsonPath("$.status").value("PENDING")).andExpect(header().string("X-Cache", "HIT"));
        assertThat(redis.getExpire(key(id))).as("unfinished payments are cached briefly").isBetween(1L, 30L);

        reconcile(); // PENDING -> SUCCESS, committed

        paymentStatusOf(lab, id).andExpect(jsonPath("$.status").value("SUCCESS")).andExpect(header().string("X-Cache", "MISS"));
        paymentStatusOf(lab, id).andExpect(header().string("X-Cache", "HIT")).andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    @Test
    void authorisationIsCheckedOnEveryHitNotJustOnTheMiss() throws Exception {
        Party payer = party("ca3");
        Party payee = party("ca3p");
        Party stranger = party("ca3s");
        Party admin = adminParty();
        String id = body(pay(payer, payee.vpa(), 5_000, key())).get("id").asText();

        paymentStatusOf(payer, id).andExpect(status().isOk());                       // populates the cache
        assertThat(redis.hasKey(key(id))).isTrue();
        paymentStatusOf(stranger, id).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"));
        paymentStatusOf(payee, id).andExpect(status().isOk()).andExpect(header().string("X-Cache", "HIT"));
        paymentStatusOf(admin, id).andExpect(status().isOk()).andExpect(header().string("X-Cache", "HIT"));
        mvc.perform(get("/api/payments/{id}/status", id)).andExpect(status().isUnauthorized());
        // An unknown id is a 404 as well (and is never cached).
        paymentStatusOf(payer, java.util.UUID.randomUUID().toString()).andExpect(status().isNotFound());
    }

    @Test
    void aCorruptOrMissingEntryFallsBackToTheDatabase() throws Exception {
        Party payer = party("ca4");
        Party payee = party("ca4p");
        String id = body(pay(payer, payee.vpa(), 5_000, key())).get("id").asText();

        redis.opsForValue().set(key(id), "{ this is not json");
        paymentStatusOf(payer, id).andExpect(status().isOk()).andExpect(header().string("X-Cache", "MISS"))
                .andExpect(jsonPath("$.status").value("SUCCESS"));
        redis.delete(key(id));
        paymentStatusOf(payer, id).andExpect(status().isOk()).andExpect(header().string("X-Cache", "MISS"));
        paymentStatusOf(payer, id).andExpect(header().string("X-Cache", "HIT"));
    }

    @Test
    void theCachedPaymentStatusEndpointDoesNotExposeInternals() throws Exception {
        Party payer = party("ca5");
        Party payee = party("ca5p");
        String id = body(pay(payer, payee.vpa(), 5_000, key())).get("id").asText();
        String response = paymentStatusOf(payer, id).andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain(payer.user().id().toString()).doesNotContain(payee.user().id().toString())
                .doesNotContain("payerUserId");
        assertThat(redis.opsForValue().get(key(id))).as("the cached copy carries the parties for authorisation only").contains("payerUserId");
    }
}
