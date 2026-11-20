package com.paysure.notification;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Webhook signatures: {@code v1 = hex( HMAC-SHA256( secret, "<timestamp>." + rawBody ) )}.
 *
 * <ul>
 *   <li>The MAC covers the exact bytes of the request body, so any modification invalidates it.</li>
 *   <li>The timestamp is part of the signed message and is checked against a tolerance window, so a captured,
 *       valid request cannot be replayed later. (Replays inside the window are absorbed by event-id deduplication.)</li>
 *   <li>Comparison is constant-time.</li>
 * </ul>
 */
public final class WebhookSigner {

    public static final String SIGNATURE_HEADER = "X-PaySure-Signature";
    public static final String TIMESTAMP_HEADER = "X-PaySure-Timestamp";
    public static final String EVENT_ID_HEADER = "X-PaySure-Event-Id";
    public static final String EVENT_TYPE_HEADER = "X-PaySure-Event-Type";
    public static final String DELIVERY_ID_HEADER = "X-PaySure-Delivery-Id";
    public static final String ENDPOINT_ID_HEADER = "X-PaySure-Endpoint-Id";

    public enum Result { VALID, MALFORMED, BAD_SIGNATURE, TIMESTAMP_OUT_OF_WINDOW }

    private WebhookSigner() {
    }

    public static String sign(String secret, long timestampSeconds, String body) {
        return "v1=" + hmacHex(secret, timestampSeconds + "." + body);
    }

    public static Result verify(String secret, String timestampHeader, String signatureHeader, String body,
                                Duration tolerance, Instant now) {
        if (timestampHeader == null || signatureHeader == null || !signatureHeader.startsWith("v1=")) {
            return Result.MALFORMED;
        }
        long ts;
        try {
            ts = Long.parseLong(timestampHeader.trim());
        } catch (NumberFormatException e) {
            return Result.MALFORMED;
        }
        byte[] expected = sign(secret, ts, body).getBytes(StandardCharsets.UTF_8);
        byte[] actual = signatureHeader.trim().getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, actual)) {
            return Result.BAD_SIGNATURE;
        }
        long skew = Math.abs(now.getEpochSecond() - ts);
        return skew > tolerance.toSeconds() ? Result.TIMESTAMP_OUT_OF_WINDOW : Result.VALID;
    }

    /** Per-endpoint secret derived from a server key, so it never has to be stored: HMAC(key, "webhook:id:version"). */
    public static String deriveSecret(String serverKey, java.util.UUID endpointId, int version) {
        byte[] mac = hmac(serverKey, "webhook:" + endpointId + ":" + version);
        return "whsec_" + Base64.getUrlEncoder().withoutPadding().encodeToString(mac);
    }

    private static String hmacHex(String secret, String message) {
        return HexFormat.of().formatHex(hmac(secret, message));
    }

    private static byte[] hmac(String secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
