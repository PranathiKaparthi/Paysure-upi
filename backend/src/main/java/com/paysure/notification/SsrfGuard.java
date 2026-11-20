package com.paysure.notification;

import com.paysure.common.error.BadRequestException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Server-side request forgery protection for webhook URLs. Applied when an endpoint is registered AND again just
 * before every delivery (an attacker could register a public name and later re-point its DNS at an internal address).
 *
 * <p>Rules: http(s) only; https required unless allow-listed; no credentials in the URL; the host must resolve and
 * <em>every</em> resolved address must be public — loopback, private (RFC 1918), link-local (incl. the cloud metadata
 * address 169.254.169.254), carrier-grade NAT, multicast, reserved, IPv6 unique-local and similar ranges are refused.
 * Redirects are never followed by the transport. Exact {@code host:port} allow-list entries exist only so the local
 * sandbox receiver can be demonstrated.
 *
 * <p>Residual risk (documented): the HTTP client resolves the name again when connecting, so a DNS-rebinding race
 * between this check and the connection is narrowed, not eliminated. A hardened deployment would pin the checked
 * address or route webhooks through an egress proxy.
 */
@Component
public class SsrfGuard {

    private final WebhookProperties props;

    public SsrfGuard(WebhookProperties props) {
        this.props = props;
    }

    public URI validate(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank() || rawUrl.length() > 500) {
            throw blocked("URL is required and must be at most 500 characters.");
        }
        URI uri;
        try {
            uri = new URI(rawUrl.trim());
        } catch (URISyntaxException e) {
            throw blocked("URL is not valid.");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw blocked("Only http and https URLs are allowed.");
        }
        if (uri.getUserInfo() != null) {
            throw blocked("URLs with embedded credentials are not allowed.");
        }
        if (uri.getFragment() != null) {
            throw blocked("URL fragments are not allowed.");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw blocked("URL has no host.");
        }
        int port = uri.getPort() != -1 ? uri.getPort() : scheme.equals("https") ? 443 : 80;
        if (port < 1 || port > 65535) {
            throw blocked("Port is out of range.");
        }
        if (isAllowlisted(host, port)) {
            return uri;
        }
        if (scheme.equals("http") && props.requireHttps()) {
            throw blocked("Only https URLs are allowed.");
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw blocked("Host cannot be resolved.");
        }
        for (InetAddress a : addresses) {
            if (isForbidden(a)) {
                throw blocked("Host resolves to a non-public address, which is not allowed.");
            }
        }
        return uri;
    }

    boolean isAllowlisted(String host, int port) {
        String h = host.toLowerCase(Locale.ROOT);
        for (String entry : props.localAllowlist()) {
            String e = entry.trim().toLowerCase(Locale.ROOT);
            int colon = e.lastIndexOf(':');
            String eh = colon < 0 ? e : e.substring(0, colon);
            String ep = colon < 0 ? "*" : e.substring(colon + 1);
            if (eh.equals(h) && (ep.equals("*") || ep.equals(Integer.toString(port)))) {
                return true;
            }
        }
        return false;
    }

    public static boolean isForbidden(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()
                || a.isMulticastAddress()) {
            return true;
        }
        byte[] b = a.getAddress();
        if (b.length == 4) {
            int b0 = b[0] & 0xff;
            int b1 = b[1] & 0xff;
            int b2 = b[2] & 0xff;
            return b0 == 0                                          // 0.0.0.0/8
                    || (b0 == 100 && b1 >= 64 && b1 <= 127)         // 100.64.0.0/10 carrier-grade NAT
                    || (b0 == 192 && b1 == 0 && b2 == 0)            // 192.0.0.0/24 IETF protocol assignments
                    || (b0 == 192 && b1 == 0 && b2 == 2)            // TEST-NET-1
                    || (b0 == 198 && (b1 == 18 || b1 == 19))        // benchmarking
                    || (b0 == 198 && b1 == 51 && b2 == 100)         // TEST-NET-2
                    || (b0 == 203 && b1 == 0 && b2 == 113)          // TEST-NET-3
                    || b0 >= 240;                                   // reserved + broadcast
        }
        if (b.length == 16) {
            int b0 = b[0] & 0xff;
            int b1 = b[1] & 0xff;
            return (b0 & 0xfe) == 0xfc                              // fc00::/7 unique local
                    || (b0 == 0x20 && b1 == 0x01 && b[2] == 0x0d && (b[3] & 0xff) == 0xb8)   // 2001:db8::/32 documentation
                    || (b0 == 0x00 && b1 == 0x64 && (b[2] & 0xff) == 0xff && (b[3] & 0xff) == 0x9b); // 64:ff9b::/96 NAT64
        }
        return true; // unknown address family: refuse
    }

    private static BadRequestException blocked(String reason) {
        return new BadRequestException("WEBHOOK_URL_BLOCKED", reason);
    }
}
