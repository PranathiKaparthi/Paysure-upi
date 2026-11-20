package com.paysure.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.paysure.notification.WebhookSigner.Result;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WebhookSignerTest {

    private static final String SECRET = "whsec_test-secret";
    private static final String BODY = "{\"eventId\":\"e1\",\"data\":{\"amountPaise\":25000}}";
    private static final Duration TOLERANCE = Duration.ofMinutes(5);
    private final Instant now = Instant.parse("2026-11-20T10:00:00Z");

    private String signed(long ts) {
        return WebhookSigner.sign(SECRET, ts, BODY);
    }

    @Test
    void aFreshCorrectSignatureIsValid() {
        long ts = now.getEpochSecond();
        assertThat(WebhookSigner.verify(SECRET, Long.toString(ts), signed(ts), BODY, TOLERANCE, now)).isEqualTo(Result.VALID);
        assertThat(signed(ts)).startsWith("v1=").hasSize(3 + 64);
    }

    @Test
    void anyChangeToTheBodyBreaksTheSignature() {
        long ts = now.getEpochSecond();
        String tampered = BODY.replace("25000", "25001");
        assertThat(WebhookSigner.verify(SECRET, Long.toString(ts), signed(ts), tampered, TOLERANCE, now)).isEqualTo(Result.BAD_SIGNATURE);
        assertThat(WebhookSigner.verify(SECRET, Long.toString(ts), signed(ts), BODY + " ", TOLERANCE, now)).isEqualTo(Result.BAD_SIGNATURE);
    }

    @Test
    void aDifferentSecretOrTimestampBreaksTheSignature() {
        long ts = now.getEpochSecond();
        assertThat(WebhookSigner.verify("whsec_other", Long.toString(ts), signed(ts), BODY, TOLERANCE, now)).isEqualTo(Result.BAD_SIGNATURE);
        // The timestamp is part of what is signed: swapping it invalidates the signature.
        assertThat(WebhookSigner.verify(SECRET, Long.toString(ts + 1), signed(ts), BODY, TOLERANCE, now)).isEqualTo(Result.BAD_SIGNATURE);
    }

    @Test
    void aValidButOldOrFutureSignatureIsRejectedAsAReplay() {
        long old = now.minus(Duration.ofMinutes(6)).getEpochSecond();
        assertThat(WebhookSigner.verify(SECRET, Long.toString(old), signed(old), BODY, TOLERANCE, now)).isEqualTo(Result.TIMESTAMP_OUT_OF_WINDOW);
        long future = now.plus(Duration.ofMinutes(6)).getEpochSecond();
        assertThat(WebhookSigner.verify(SECRET, Long.toString(future), signed(future), BODY, TOLERANCE, now)).isEqualTo(Result.TIMESTAMP_OUT_OF_WINDOW);
        long edge = now.minus(Duration.ofMinutes(5)).getEpochSecond();
        assertThat(WebhookSigner.verify(SECRET, Long.toString(edge), signed(edge), BODY, TOLERANCE, now)).isEqualTo(Result.VALID);
    }

    @Test
    void malformedHeadersAreRejected() {
        long ts = now.getEpochSecond();
        assertThat(WebhookSigner.verify(SECRET, null, signed(ts), BODY, TOLERANCE, now)).isEqualTo(Result.MALFORMED);
        assertThat(WebhookSigner.verify(SECRET, Long.toString(ts), null, BODY, TOLERANCE, now)).isEqualTo(Result.MALFORMED);
        assertThat(WebhookSigner.verify(SECRET, "yesterday", signed(ts), BODY, TOLERANCE, now)).isEqualTo(Result.MALFORMED);
        assertThat(WebhookSigner.verify(SECRET, Long.toString(ts), "sha256=" + signed(ts).substring(3), BODY, TOLERANCE, now)).isEqualTo(Result.MALFORMED);
        assertThat(WebhookSigner.verify(SECRET, Long.toString(ts), "v1=", BODY, TOLERANCE, now)).isEqualTo(Result.BAD_SIGNATURE);
    }

    @Test
    void derivedSecretsAreStablePerEndpointAndVersionButNeverEqual() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        String key = "server-key";
        assertThat(WebhookSigner.deriveSecret(key, a, 1)).isEqualTo(WebhookSigner.deriveSecret(key, a, 1)).startsWith("whsec_");
        assertThat(WebhookSigner.deriveSecret(key, a, 1)).isNotEqualTo(WebhookSigner.deriveSecret(key, a, 2))
                .isNotEqualTo(WebhookSigner.deriveSecret(key, b, 1)).isNotEqualTo(WebhookSigner.deriveSecret("other-key", a, 1));
    }
}
