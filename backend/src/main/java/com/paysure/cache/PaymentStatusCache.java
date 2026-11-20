package com.paysure.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paysure.ratelimit.RedisGuard;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Short-lived read-through cache for the payment status endpoint. PostgreSQL stays the system of record:
 * <ul>
 *   <li>the cache is only ever a copy; every Redis failure is swallowed and the caller reads the database;</li>
 *   <li>the entry is deleted <em>after the transaction that changed the payment commits</em>;</li>
 *   <li>entries expire anyway (short for unfinished payments, longer for final ones, which never change).</li>
 * </ul>
 * Known race: a reader may load the old row, lose a race with a committing writer's invalidation, and then store the
 * stale value. The TTL bounds that staleness (default 30 s for unfinished payments); final states cannot go stale.
 */
@Component
public class PaymentStatusCache {

    @ConfigurationProperties("paysure.cache")
    public record Properties(@DefaultValue("true") boolean paymentStatusEnabled,
                             @DefaultValue("30s") Duration unfinishedTtl,
                             @DefaultValue("5m") Duration finalTtl) {
    }

    /** What is cached. Includes the parties so authorisation is still checked on a cache hit. */
    public record Entry(UUID paymentId, String status, String holdStatus, String failureCode, long amountPaise,
                        UUID payerUserId, UUID payeeUserId, Instant updatedAt) {
    }

    private static final Logger log = LoggerFactory.getLogger(PaymentStatusCache.class);
    private static final String PREFIX = "ps:payment-status:";

    private final StringRedisTemplate redis;
    private final RedisGuard guard;
    private final Properties props;
    private final ObjectMapper mapper;
    private final MeterRegistry registry;

    public PaymentStatusCache(StringRedisTemplate redis, RedisGuard guard, Properties props, ObjectMapper mapper,
                              MeterRegistry registry) {
        this.redis = redis;
        this.guard = guard;
        this.props = props;
        this.mapper = mapper;
        this.registry = registry;
        for (String result : java.util.List.of("hit", "miss", "error")) {
            registry.counter("payment.status.cache", "result", result);
        }
    }

    public Optional<Entry> get(UUID paymentId) {
        if (!props.paymentStatusEnabled() || !guard.available()) {
            return Optional.empty();
        }
        try {
            String json = redis.opsForValue().get(PREFIX + paymentId);
            registry.counter("payment.status.cache", "result", json == null ? "miss" : "hit").increment();
            return json == null ? Optional.empty() : Optional.of(mapper.readValue(json, Entry.class));
        } catch (RuntimeException | JsonProcessingException e) {
            registry.counter("payment.status.cache", "result", "error").increment();
            if (e instanceof RuntimeException re) {
                guard.failure(re);
            } else {
                log.warn("Unreadable payment-status cache entry for {}: {}", paymentId, e.getMessage());
                invalidate(paymentId);
            }
            return Optional.empty();
        }
    }

    public void put(Entry entry) {
        if (!props.paymentStatusEnabled() || !guard.available()) {
            return;
        }
        boolean terminal = "SUCCESS".equals(entry.status()) || "FAILED".equals(entry.status());
        try {
            redis.opsForValue().set(PREFIX + entry.paymentId(), mapper.writeValueAsString(entry),
                    terminal ? props.finalTtl() : props.unfinishedTtl());
        } catch (RuntimeException e) {
            guard.failure(e);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public void invalidate(UUID paymentId) {
        if (!props.paymentStatusEnabled() || !guard.available()) {
            return;
        }
        try {
            redis.delete(PREFIX + paymentId);
        } catch (RuntimeException e) {
            guard.failure(e); // the TTL still bounds staleness
        }
    }

    /** Invalidates once the surrounding transaction has committed (immediately if there is none). */
    public void invalidateAfterCommit(UUID paymentId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    invalidate(paymentId);
                }
            });
        } else {
            invalidate(paymentId);
        }
    }
}
