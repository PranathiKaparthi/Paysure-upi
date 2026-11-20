package com.paysure.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paysure.common.error.Problems;
import com.paysure.ratelimit.RateLimitProperties.Rule;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Applies per-user and per-IP limits to money-moving and authentication endpoints. Runs after authentication, so the
 * user id comes from the verified JWT. Rejections are RFC 7807 (429) with Retry-After.
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RateLimiter limiter;
    private final RateLimitProperties props;
    private final ObjectMapper mapper;

    public RateLimitInterceptor(RateLimiter limiter, RateLimitProperties props, ObjectMapper mapper) {
        this.limiter = limiter;
        this.props = props;
        this.mapper = mapper;
    }

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull Object handler)
            throws IOException {
        if (!props.enabled() || !"POST".equals(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        String ip = clientIp(request);
        String user = userId();
        RateLimiter.Decision tightest = null;
        if (path.equals("/api/payments")) {
            tightest = worse(tightest, apply("payments-ip", ip, props.paymentsPerIp(), response));
            tightest = worse(tightest, user == null ? null : apply("payments-user", user, props.paymentsPerUser(), response));
        } else if (path.equals("/api/collect-requests")) {
            tightest = worse(tightest, user == null ? null : apply("collect-user", user, props.collectPerUser(), response));
        } else if (path.equals("/api/auth/login") || path.equals("/api/auth/register")) {
            tightest = worse(tightest, apply("auth-ip", ip, props.authPerIp(), response));
        } else {
            return true;
        }
        if (tightest != null) {
            response.setHeader("X-RateLimit-Limit", Integer.toString(tightest.limit()));
            response.setHeader("X-RateLimit-Remaining", Long.toString(tightest.remaining()));
            if (!tightest.allowed()) {
                response.setHeader("Retry-After", Long.toString(Math.max(1, tightest.retryAfterSeconds())));
                response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
                mapper.writeValue(response.getOutputStream(), Problems.of(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                        "Too many requests. Retry after " + Math.max(1, tightest.retryAfterSeconds()) + " seconds."));
                return false;
            }
        }
        return true;
    }

    private RateLimiter.Decision apply(String policy, String subject, Rule rule, HttpServletResponse response) {
        return limiter.check(policy, subject, rule.limit(), rule.windowSeconds());
    }

    /** Prefers a rejection; otherwise the decision with the fewest requests left. */
    private static RateLimiter.Decision worse(RateLimiter.Decision a, RateLimiter.Decision b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        if (a.allowed() != b.allowed()) {
            return a.allowed() ? b : a;
        }
        return a.remaining() <= b.remaining() ? a : b;
    }

    private String clientIp(HttpServletRequest request) {
        if (props.trustForwardedFor()) {
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                return xff.split(",")[0].trim();
            }
        }
        return request.getRemoteAddr();
    }

    private static String userId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof Jwt jwt ? jwt.getSubject() : null;
    }
}
