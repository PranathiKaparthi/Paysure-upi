package com.paysure.ratelimit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.stereotype.Component;

/**
 * Tiny circuit breaker shared by everything that talks to Redis. After a Redis error, Redis is skipped for
 * {@code cooldown} so requests do not each pay the connection timeout. Redis is an optimisation here:
 * PostgreSQL stays the system of record, so every caller has a Redis-free fallback.
 */
@Component
public class RedisGuard {

    @ConfigurationProperties("paysure.redis")
    public record Properties(@DefaultValue("5s") Duration cooldown) {
    }

    private static final Logger log = LoggerFactory.getLogger(RedisGuard.class);

    private final Properties props;
    private final Clock clock;
    private final Counter errors;
    private volatile Instant downUntil = Instant.MIN;
    private volatile Instant lastWarned = Instant.MIN;

    public RedisGuard(Properties props, Clock clock, MeterRegistry registry) {
        this.props = props;
        this.clock = clock;
        this.errors = Counter.builder("redis.errors").description("Redis calls that failed (callers fell back)").register(registry);
    }

    public boolean available() {
        return !clock.instant().isBefore(downUntil);
    }

    public void failure(Exception e) {
        errors.increment();
        Instant now = clock.instant();
        downUntil = now.plus(props.cooldown());
        if (now.isAfter(lastWarned.plusSeconds(30))) { // do not flood the log during an outage
            lastWarned = now;
            log.warn("Redis unavailable ({}); using database / in-memory fallbacks for {}", e.toString(), props.cooldown());
        }
    }

    /** Forget a previous failure (used by tests and after a successful call). */
    public void reset() {
        downUntil = Instant.MIN;
    }
}
