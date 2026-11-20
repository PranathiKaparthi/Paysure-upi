package com.paysure.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.paysure.common.error.BadRequestException;
import java.net.InetAddress;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Uses IP-literal hosts only, so no DNS lookups (and no network) are needed. */
class SsrfGuardTest {

    private static WebhookProperties props(List<String> allow) {
        return new WebhookProperties("http", false, 1000, 10, Duration.ofMinutes(2), 6, Duration.ofSeconds(5),
                Duration.ofMinutes(5), Duration.ofSeconds(3), Duration.ofSeconds(5), Duration.ofMinutes(5), 5, true, allow,
                false, "http://localhost:8080/webhook-sandbox/receiver", null);
    }

    private final SsrfGuard guard = new SsrfGuard(props(List.of()));

    @ParameterizedTest
    @ValueSource(strings = {
            "https://127.0.0.1/hook", "https://127.1/hook", "https://2130706433/hook", "https://0.0.0.0/hook",
            "https://10.0.0.5/hook", "https://172.16.5.1/hook", "https://172.31.255.255/hook", "https://192.168.1.10/hook",
            "https://169.254.169.254/latest/meta-data/", "https://100.64.0.1/hook", "https://198.18.0.1/hook",
            "https://224.0.0.1/hook", "https://240.0.0.1/hook", "https://255.255.255.255/hook",
            "https://[::1]/hook", "https://[::]/hook", "https://[fd00::1]/hook", "https://[fe80::1]/hook",
            "https://[::ffff:127.0.0.1]/hook", "https://[::ffff:10.0.0.1]/hook", "https://[2001:db8::1]/hook"})
    void nonPublicAddressesAreBlocked(String url) {
        assertThatThrownBy(() -> guard.validate(url)).isInstanceOf(BadRequestException.class)
                .extracting(e -> ((BadRequestException) e).code()).isEqualTo("WEBHOOK_URL_BLOCKED");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ftp://93.184.216.34/hook", "file:///etc/passwd", "gopher://93.184.216.34/", "javascript:alert(1)",
            "https://user:pass@93.184.216.34/hook", "https://93.184.216.34/hook#frag", "https:///nohost", "not a url", "",
            "http://93.184.216.34/hook" /* http not allowed: https required */})
    void badSchemesCredentialsFragmentsAndPlainHttpAreBlocked(String url) {
        assertThatThrownBy(() -> guard.validate(url)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void publicAddressesOverHttpsAreAllowed() {
        assertThatCode(() -> guard.validate("https://93.184.216.34/hook")).doesNotThrowAnyException();
        assertThatCode(() -> guard.validate("https://8.8.8.8:8443/hook?x=1")).doesNotThrowAnyException();
        assertThatCode(() -> guard.validate("https://[2606:2800:220:1:248:1893:25c8:1946]/hook")).doesNotThrowAnyException();
    }

    @Test
    void anOverlongUrlIsRejected() {
        assertThatThrownBy(() -> guard.validate("https://93.184.216.34/" + "a".repeat(600))).isInstanceOf(BadRequestException.class);
    }

    @Test
    void theLocalAllowlistIsExactHostAndPortOnly() {
        SsrfGuard local = new SsrfGuard(props(List.of("localhost:8080", "127.0.0.1:9000")));
        assertThatCode(() -> local.validate("http://localhost:8080/webhook-sandbox/receiver")).doesNotThrowAnyException();
        assertThatCode(() -> local.validate("http://127.0.0.1:9000/x")).doesNotThrowAnyException();
        // Another port, another loopback host, or an internal address are still refused.
        assertThatThrownBy(() -> local.validate("http://localhost:8081/x")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> local.validate("http://127.0.0.2:9000/x")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> local.validate("http://10.0.0.1:8080/x")).isInstanceOf(BadRequestException.class);
        // A wildcard port opens one host only.
        SsrfGuard wildcard = new SsrfGuard(props(List.of("localhost:*")));
        assertThatCode(() -> wildcard.validate("http://localhost:54321/x")).doesNotThrowAnyException();
        assertThatThrownBy(() -> wildcard.validate("http://127.0.0.1:54321/x")).isInstanceOf(BadRequestException.class);
    }

    @Test
    void addressClassifierCoversTheDocumentedRanges() throws Exception {
        assertThat(SsrfGuard.isForbidden(InetAddress.getByName("8.8.8.8"))).isFalse();
        assertThat(SsrfGuard.isForbidden(InetAddress.getByName("93.184.216.34"))).isFalse();
        assertThat(SsrfGuard.isForbidden(InetAddress.getByName("100.63.255.255"))).isFalse();
        assertThat(SsrfGuard.isForbidden(InetAddress.getByName("100.64.0.0"))).isTrue();
        assertThat(SsrfGuard.isForbidden(InetAddress.getByName("100.127.255.255"))).isTrue();
        assertThat(SsrfGuard.isForbidden(InetAddress.getByName("100.128.0.0"))).isFalse();
        assertThat(SsrfGuard.isForbidden(InetAddress.getByName("172.15.0.1"))).isFalse();
        assertThat(SsrfGuard.isForbidden(InetAddress.getByName("172.16.0.1"))).isTrue();
        assertThat(SsrfGuard.isForbidden(InetAddress.getByName("172.32.0.1"))).isFalse();
    }
}
