package com.paysure.notification;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param transport              {@code http} (default) or {@code recording} (tests)
 * @param workerEnabled          run the scheduled delivery worker
 * @param pollMillis             delay between worker polls
 * @param batchSize              deliveries claimed per poll
 * @param lease                  a claimed delivery that is not finished within this time may be claimed again
 * @param maxAttempts            attempts before a delivery is dead-lettered (status DEAD)
 * @param backoffBase            first retry delay ceiling; doubles each attempt
 * @param backoffCap             upper bound of the retry delay ceiling
 * @param replayTolerance        receivers reject signatures whose timestamp is further than this from "now"
 * @param maxEndpointsPerUser    registered endpoints per user
 * @param requireHttps           only https URLs may be registered (except allow-listed local hosts)
 * @param localAllowlist         exact {@code host:port} (or {@code host:*}) entries exempt from the private-network and
 *                               https rules — for the local sandbox receiver only; empty in production
 * @param sandboxReceiverEnabled expose the built-in test receiver at /webhook-sandbox/receiver
 * @param sandboxReceiverUrl     URL under which this backend reaches its own receiver (shown in the UI)
 * @param secretKey              server key that endpoint secrets are derived from; falls back to the JWT secret
 */
@ConfigurationProperties("paysure.webhooks")
public record WebhookProperties(
        @DefaultValue("http") String transport,
        @DefaultValue("true") boolean workerEnabled,
        @DefaultValue("1000") long pollMillis,
        @DefaultValue("10") int batchSize,
        @DefaultValue("2m") Duration lease,
        @DefaultValue("6") int maxAttempts,
        @DefaultValue("5s") Duration backoffBase,
        @DefaultValue("5m") Duration backoffCap,
        @DefaultValue("3s") Duration connectTimeout,
        @DefaultValue("5s") Duration requestTimeout,
        @DefaultValue("5m") Duration replayTolerance,
        @DefaultValue("5") int maxEndpointsPerUser,
        @DefaultValue("true") boolean requireHttps,
        List<String> localAllowlist,
        @DefaultValue("false") boolean sandboxReceiverEnabled,
        @DefaultValue("http://localhost:8080/webhook-sandbox/receiver") String sandboxReceiverUrl,
        String secretKey) {

    public WebhookProperties {
        localAllowlist = localAllowlist == null ? List.of() : List.copyOf(localAllowlist);
    }
}
