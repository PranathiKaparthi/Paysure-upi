package com.paysure.ratelimit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.paysure.support.IntegrationTest;
import com.paysure.support.TestRedis;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Login/registration are limited per client IP (credential-stuffing protection). Uses private client addresses only. */
@TestPropertySource(properties = {"paysure.rate-limit.enabled=true", "paysure.rate-limit.auth-per-ip.limit=3"})
class AuthRateLimitTest extends IntegrationTest {

    @DynamicPropertySource
    static void redisProps(DynamicPropertyRegistry registry) {
        TestRedis.Connection r = TestRedis.get();
        registry.add("spring.data.redis.host", r::host);
        registry.add("spring.data.redis.port", r::port);
    }

    private static RequestPostProcessor from(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    private org.springframework.test.web.servlet.ResultActions loginFrom(String ip) throws Exception {
        return mvc.perform(post("/api/auth/login").with(from(ip)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", "nobody@example.com", "password", "wrong-password1"))));
    }

    @Test
    void repeatedLoginAttemptsFromOneAddressAreThrottledAndOtherAddressesAreNot() throws Exception {
        String attacker = "203.0.113.11"; // fixed and distinct per test: each address has its own counter
        for (int i = 0; i < 3; i++) {
            loginFrom(attacker).andExpect(status().isUnauthorized());
        }
        loginFrom(attacker).andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        // A different client is unaffected, and a wrong password is still a 401, not a 429.
        loginFrom("198.51.100.77").andExpect(status().isUnauthorized());
    }

    @Test
    void forwardedForIsIgnoredUnlessExplicitlyTrusted() throws Exception {
        String ip = "203.0.113.12";
        // A client cannot dodge the limit by sending rotating X-Forwarded-For values (default: not trusted).
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/auth/login").with(from(ip)).header("X-Forwarded-For", "10.9.9." + i)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("email", "nobody@example.com", "password", "wrong-password1"))))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/auth/login").with(from(ip)).header("X-Forwarded-For", "10.9.9.99")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", "nobody@example.com", "password", "wrong-password1"))))
                .andExpect(status().isTooManyRequests());
    }
}
