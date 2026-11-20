package com.paysure.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Fixed-window limits. A caller can burst up to twice a limit across a window boundary; that is the accepted
 * trade-off for a single atomic Redis round trip.
 *
 * @param trustForwardedFor use the first X-Forwarded-For address as the client IP. Only enable behind a proxy that
 *                          sets it (the Docker nginx does); otherwise clients could spoof their IP.
 */
@ConfigurationProperties("paysure.rate-limit")
public record RateLimitProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("false") boolean trustForwardedFor,
        @DefaultValue Rule paymentsPerUser,
        @DefaultValue Rule paymentsPerIp,
        @DefaultValue Rule authPerIp,
        @DefaultValue Rule collectPerUser) {

    public record Rule(@DefaultValue("30") int limit, @DefaultValue("60") int windowSeconds) {
    }

    public RateLimitProperties {
        paymentsPerUser = paymentsPerUser == null ? new Rule(30, 60) : paymentsPerUser;
        paymentsPerIp = paymentsPerIp == null ? new Rule(120, 60) : paymentsPerIp;
        authPerIp = authPerIp == null ? new Rule(20, 60) : authPerIp;
        collectPerUser = collectPerUser == null ? new Rule(30, 60) : collectPerUser;
    }
}
