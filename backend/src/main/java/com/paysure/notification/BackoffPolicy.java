package com.paysure.notification;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.random.RandomGenerator;
import org.springframework.stereotype.Component;

/**
 * Bounded exponential backoff with "equal jitter":
 * {@code ceiling = min(cap, base × 2^(attempt−1))}, {@code delay = ceiling/2 + random(0, ceiling/2)}.
 * The delay is therefore always within [ceiling/2, ceiling] — never zero, never above the cap — and retries of many
 * deliveries that failed together do not all land on the same instant.
 */
@Component
public class BackoffPolicy {

    private final WebhookProperties props;
    private final RandomGenerator random;

    @org.springframework.beans.factory.annotation.Autowired
    public BackoffPolicy(WebhookProperties props) {
        this(props, new SecureRandom());
    }

    public BackoffPolicy(WebhookProperties props, RandomGenerator random) {
        this.props = props;
        this.random = random;
    }

    /** @param attemptsMade number of attempts already made (≥ 1) */
    public Duration delayAfter(int attemptsMade) {
        long baseMs = props.backoffBase().toMillis();
        long capMs = props.backoffCap().toMillis();
        int exponent = Math.max(0, Math.min(attemptsMade - 1, 30));
        long ceiling = Math.min(capMs, baseMs * (1L << exponent));
        if (ceiling <= 1) {
            return Duration.ofMillis(Math.max(0, ceiling));
        }
        long half = ceiling / 2;
        return Duration.ofMillis(half + random.nextLong(half + 1));
    }

    public Duration ceilingAfter(int attemptsMade) {
        int exponent = Math.max(0, Math.min(attemptsMade - 1, 30));
        return Duration.ofMillis(Math.min(props.backoffCap().toMillis(), props.backoffBase().toMillis() * (1L << exponent)));
    }
}
