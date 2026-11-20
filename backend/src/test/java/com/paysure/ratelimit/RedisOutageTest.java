package com.paysure.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.paysure.support.PaymentTestBase;
import com.paysure.support.TestRedis;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import redis.embedded.RedisServer;

/**
 * Mandatory case 23: Redis temporarily unavailable. Payments must keep working (PostgreSQL is the system of record),
 * limits must keep applying in a degraded in-memory mode, and Redis must be used again once it is back.
 * Uses its own private redis-server process so it can be stopped and restarted.
 */
@TestPropertySource(properties = {
        "paysure.rate-limit.enabled=true",
        "paysure.rate-limit.payments-per-user.limit=2",
        "paysure.rate-limit.payments-per-ip.limit=100000",
        "paysure.rate-limit.auth-per-ip.limit=100000",
        "paysure.cache.payment-status-enabled=true",
        "paysure.redis.cooldown=1s"})
class RedisOutageTest extends PaymentTestBase {

    private static int port;
    private static RedisServer server;

    @Autowired
    RateLimiter limiter;
    @Autowired
    RedisGuard guard;
    @Autowired
    StringRedisTemplate redis;
    @Autowired
    MeterRegistry registry;

    @DynamicPropertySource
    static void ownRedis(DynamicPropertyRegistry registry) {
        port = TestRedis.freePort();
        server = TestRedis.startEmbedded(port);
        registry.add("spring.data.redis.host", () -> "localhost");
        registry.add("spring.data.redis.port", () -> port);
    }

    @AfterAll
    static void stopRedis() {
        try {
            server.stop();
        } catch (RuntimeException ignored) {
            // already stopped
        }
    }

    @Test
    void case23_paymentsKeepWorkingDuringARedisOutageAndRedisIsUsedAgainAfterwards() throws Exception {
        Party payee = party("ro-payee");

        // ---- Redis healthy: limits and cache are served from Redis.
        Party before = party("ro1");
        pay(before, payee.vpa(), 100, key()).andExpect(status().isCreated());
        pay(before, payee.vpa(), 100, key()).andExpect(status().isCreated());
        pay(before, payee.vpa(), 100, key()).andExpect(status().isTooManyRequests());
        assertThat(limiter.check("probe", "healthy-" + System.nanoTime(), 5, 60).degraded()).isFalse();

        // ---- Redis goes down.
        server.stop();
        double errorsBefore = registry.counter("redis.errors").count();
        Party during = party("ro2");
        String paymentId;
        long started = System.nanoTime();
        paymentId = body(pay(during, payee.vpa(), 100, key()).andExpect(status().isCreated())).get("id").asText();
        pay(during, payee.vpa(), 100, key()).andExpect(status().isCreated());
        // The limit still applies, enforced by the per-instance fallback.
        pay(during, payee.vpa(), 100, key()).andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        // The status endpoint reads PostgreSQL; the cache is simply bypassed.
        mvc.perform(get("/api/payments/{id}/status", paymentId).header("Authorization", during.bearer()))
                .andExpect(status().isOk()).andExpect(header().string("X-Cache", "MISS")).andExpect(jsonPath("$.status").value("SUCCESS"));
        mvc.perform(get("/api/payments/{id}/status", paymentId).header("Authorization", during.bearer()))
                .andExpect(header().string("X-Cache", "MISS"));
        Duration took = Duration.ofNanos(System.nanoTime() - started);
        assertThat(took).as("fail-fast: an outage must not stall requests on connection timeouts").isLessThan(Duration.ofSeconds(8));
        assertThat(registry.counter("redis.errors").count()).isGreaterThan(errorsBefore);
        assertThat(limiter.check("probe", "outage-" + System.nanoTime(), 5, 60).degraded()).isTrue();

        // Money is exactly right regardless of Redis.
        assertThat(balance(during.accountId())).isEqualTo(SEED - 200);
        assertThat(balance(before.accountId())).isEqualTo(SEED - 200);
        assertLedgerInvariants();

        // ---- Redis comes back (empty): after the cooldown the limiter and cache use it again.
        server = TestRedis.startEmbedded(port);
        Party after = party("ro3");
        Awaitility.await().atMost(Duration.ofSeconds(40)).pollInterval(Duration.ofMillis(500)).untilAsserted(() ->
                assertThat(limiter.check("probe-back", "back-" + System.nanoTime(), 5, 60).degraded()).isFalse());
        String afterPayment = body(pay(after, payee.vpa(), 100, key()).andExpect(status().isCreated())).get("id").asText();
        assertThat(redis.hasKey("ps:rl:payments-user:" + after.user().id())).as("counter is back in Redis").isTrue();
        mvc.perform(get("/api/payments/{id}/status", afterPayment).header("Authorization", after.bearer()))
                .andExpect(header().string("X-Cache", "MISS"));
        mvc.perform(get("/api/payments/{id}/status", afterPayment).header("Authorization", after.bearer()))
                .andExpect(header().string("X-Cache", "HIT"));
    }

    @Test
    void theGuardOpensAfterAFailureAndClosesAfterTheCooldown() throws Exception {
        guard.reset();
        assertThat(guard.available()).isTrue();
        guard.failure(new IllegalStateException("boom"));
        assertThat(guard.available()).isFalse();
        Thread.sleep(1200);
        assertThat(guard.available()).isTrue();
    }
}
