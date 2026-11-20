package com.paysure.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class BackoffPolicyTest {

    private static WebhookProperties props(Duration base, Duration cap) {
        return new WebhookProperties("http", false, 1000, 10, Duration.ofMinutes(2), 6, base, cap, Duration.ofSeconds(3),
                Duration.ofSeconds(5), Duration.ofMinutes(5), 5, true, List.of(), false, "u", null);
    }

    @Test
    void delaysGrowExponentiallyAndStayWithinHalfToFullCeiling() {
        BackoffPolicy policy = new BackoffPolicy(props(Duration.ofSeconds(5), Duration.ofMinutes(5)), new Random(7));
        for (int attempt = 1; attempt <= 6; attempt++) {
            long ceiling = policy.ceilingAfter(attempt).toMillis();
            assertThat(ceiling).isEqualTo(Math.min(300_000, 5_000L << (attempt - 1)));
            for (int i = 0; i < 200; i++) {
                long d = policy.delayAfter(attempt).toMillis();
                assertThat(d).as("attempt %d", attempt).isBetween(ceiling / 2, ceiling);
            }
        }
    }

    @Test
    void theCapBoundsEveryDelayEvenForHugeAttemptCounts() {
        BackoffPolicy policy = new BackoffPolicy(props(Duration.ofSeconds(5), Duration.ofMinutes(5)), new Random(1));
        for (int attempt : new int[] {7, 10, 30, 100, 10_000}) {
            assertThat(policy.ceilingAfter(attempt)).isEqualTo(Duration.ofMinutes(5));
            assertThat(policy.delayAfter(attempt)).isLessThanOrEqualTo(Duration.ofMinutes(5));
        }
    }

    @Test
    void jitterSpreadsRetriesSoTheyDoNotAllLandTogether() {
        BackoffPolicy policy = new BackoffPolicy(props(Duration.ofSeconds(10), Duration.ofMinutes(5)), new Random(42));
        long distinct = java.util.stream.IntStream.range(0, 100).mapToObj(i -> policy.delayAfter(3).toMillis()).distinct().count();
        assertThat(distinct).isGreaterThan(50);
        assertThat(policy.delayAfter(1)).isGreaterThan(Duration.ZERO);
    }
}
