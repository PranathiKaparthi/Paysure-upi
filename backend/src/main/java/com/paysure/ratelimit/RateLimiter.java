package com.paysure.ratelimit;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Per-subject fixed-window rate limiter.
 *
 * <p><b>Redis path:</b> one Lua script does {@code INCR}, sets the window expiry on the first hit and reads the
 * remaining TTL — atomically, so concurrent requests can never over-admit or leave a counter without an expiry.
 *
 * <p><b>Fallback:</b> if Redis is unreachable the request is NOT failed (payments must not depend on Redis); a
 * per-instance in-memory window is used instead, so some protection remains during an outage.
 */
@Component
public class RateLimiter {

    static final String LUA = """
            local c = redis.call('INCR', KEYS[1])
            if c == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end
            local ttl = redis.call('PTTL', KEYS[1])
            if ttl < 0 then redis.call('PEXPIRE', KEYS[1], ARGV[1]); ttl = tonumber(ARGV[1]) end
            return {c, ttl}""";

    public record Decision(boolean allowed, int limit, long remaining, long retryAfterSeconds, boolean degraded) {
    }

    private final StringRedisTemplate redis;
    private final RedisGuard guard;
    private final Clock clock;
    private final MeterRegistry registry;
    private final DefaultRedisScript<List> script = new DefaultRedisScript<>(LUA, List.class);
    private final Map<String, Window> local = new ConcurrentHashMap<>();

    public RateLimiter(StringRedisTemplate redis, RedisGuard guard, Clock clock, MeterRegistry registry) {
        this.redis = redis;
        this.guard = guard;
        this.clock = clock;
        this.registry = registry;
        for (String policy : List.of("payments-user", "payments-ip", "collect-user", "auth-ip")) {
            registry.counter("ratelimit.rejected", "policy", policy);
        }
    }

    /** @param policy bounded set of rule names (also a metric tag); @param subject user id or IP */
    public Decision check(String policy, String subject, int limit, int windowSeconds) {
        String key = "ps:rl:" + policy + ":" + subject;
        Decision d = null;
        if (guard.available()) {
            try {
                @SuppressWarnings("unchecked")
                List<Long> r = (List<Long>) redis.execute(script, List.of(key), Long.toString(windowSeconds * 1000L));
                if (r != null && r.size() == 2) {
                    long count = r.get(0);
                    long ttlMs = Math.max(0, r.get(1));
                    d = new Decision(count <= limit, limit, Math.max(0, limit - count), (long) Math.ceil(ttlMs / 1000.0), false);
                }
            } catch (RuntimeException e) {
                guard.failure(e);
            }
        }
        if (d == null) {
            d = checkLocal(key, limit, windowSeconds);
        }
        if (!d.allowed()) {
            registry.counter("ratelimit.rejected", "policy", policy).increment();
        }
        return d;
    }

    private Decision checkLocal(String key, int limit, int windowSeconds) {
        long now = clock.millis();
        Window w = local.compute(key, (k, old) -> old == null || now >= old.resetAt
                ? new Window(1, now + windowSeconds * 1000L) : new Window(old.count + 1, old.resetAt));
        if (local.size() > 10_000) {
            local.entrySet().removeIf(e -> now >= e.getValue().resetAt);
        }
        return new Decision(w.count <= limit, limit, Math.max(0, limit - w.count),
                (long) Math.ceil(Math.max(0, w.resetAt - now) / 1000.0), true);
    }

    private record Window(long count, long resetAt) {
    }
}
