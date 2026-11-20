package com.paysure.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.paysure.support.PaymentTestBase;
import com.paysure.support.TestRedis;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/** Rate limiting against a REAL Redis (Testcontainers if Docker exists, else a real redis-server process). */
@TestPropertySource(properties = {
        "paysure.rate-limit.enabled=true",
        "paysure.rate-limit.payments-per-user.limit=3",
        "paysure.rate-limit.payments-per-ip.limit=100000",
        "paysure.rate-limit.auth-per-ip.limit=100000",   // parties are registered from one IP during setup
        "paysure.rate-limit.collect-per-user.limit=2"})
class RateLimitIntegrationTest extends PaymentTestBase {

    @Autowired
    RateLimiter limiter;
    @Autowired
    StringRedisTemplate redis;

    @DynamicPropertySource
    static void redisProps(DynamicPropertyRegistry registry) {
        TestRedis.Connection r = TestRedis.get();
        registry.add("spring.data.redis.host", r::host);
        registry.add("spring.data.redis.port", r::port);
    }

    @Test
    void aUserIsLimitedPerWindowWithStandardHeadersAndOthersAreUnaffected() throws Exception {
        Party payer = party("rl1");
        Party payee = party("rl1p");
        Party other = party("rl1o");

        for (int i = 0; i < 3; i++) {
            pay(payer, payee.vpa(), 100, key()).andExpect(status().isCreated())
                    .andExpect(header().string("X-RateLimit-Limit", "3"))
                    .andExpect(header().string("X-RateLimit-Remaining", Integer.toString(2 - i)));
        }
        pay(payer, payee.vpa(), 100, key())
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(header().string("X-RateLimit-Remaining", "0"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.status").value(429));
        assertThat(paymentsFrom(payer.accountId())).as("the rejected request created nothing").isEqualTo(3);

        pay(other, payee.vpa(), 100, key()).andExpect(status().isCreated());
        // Money correctness is untouched by limiting.
        assertThat(balance(payer.accountId())).isEqualTo(SEED - 300);
        assertLedgerInvariants();

        // The window counter lives in Redis and always has an expiry (set atomically with the first increment).
        Long ttl = redis.getExpire("ps:rl:payments-user:" + payer.user().id());
        assertThat(ttl).isBetween(1L, 60L);
    }

    @Test
    void collectRequestsHaveTheirOwnPolicy() throws Exception {
        Party a = party("rl2");
        Party b = party("rl2b");
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/collect-requests").header("Authorization", a.bearer()).contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("requesterVpa", a.vpa(), "payerVpa", b.vpa(), "amountPaise", 100))))
                    .andExpect(status().isCreated());
        }
        mvc.perform(post("/api/collect-requests").header("Authorization", a.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("requesterVpa", a.vpa(), "payerVpa", b.vpa(), "amountPaise", 100))))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    void theLuaScriptIsAtomicSoConcurrentRequestsNeverOverAdmit() throws Exception {
        String subject = "concurrent-" + UUID.randomUUID();
        List<RateLimiter.Decision> decisions = runConcurrently(40, () -> limiter.check("t-concurrent", subject, 5, 60));

        assertThat(decisions).filteredOn(RateLimiter.Decision::allowed).hasSize(5);
        assertThat(decisions).noneMatch(RateLimiter.Decision::degraded);
        assertThat(redis.opsForValue().get("ps:rl:t-concurrent:" + subject)).isEqualTo("40");
        assertThat(redis.getExpire("ps:rl:t-concurrent:" + subject)).isBetween(1L, 60L);
    }

    @Test
    void theWindowResetsAfterItsTtl() throws Exception {
        String subject = "expiry-" + UUID.randomUUID();
        assertThat(limiter.check("t-expiry", subject, 2, 1).allowed()).isTrue();
        assertThat(limiter.check("t-expiry", subject, 2, 1).allowed()).isTrue();
        RateLimiter.Decision denied = limiter.check("t-expiry", subject, 2, 1);
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterSeconds()).isBetween(0L, 1L);

        Thread.sleep(1300);
        assertThat(limiter.check("t-expiry", subject, 2, 1).allowed()).as("new window").isTrue();
    }

    @Test
    void subjectsAndPoliciesAreIndependent() {
        String s1 = "ind-" + UUID.randomUUID();
        String s2 = "ind-" + UUID.randomUUID();
        assertThat(limiter.check("t-a", s1, 1, 60).allowed()).isTrue();
        assertThat(limiter.check("t-a", s1, 1, 60).allowed()).isFalse();
        assertThat(limiter.check("t-a", s2, 1, 60).allowed()).isTrue();
        assertThat(limiter.check("t-b", s1, 1, 60).allowed()).isTrue();
    }
}
