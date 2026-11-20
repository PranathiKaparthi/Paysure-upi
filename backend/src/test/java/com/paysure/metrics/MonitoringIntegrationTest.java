package com.paysure.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.paysure.support.ReliabilityTestBase;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The System Monitoring API and the Prometheus metrics contract (names present, labels bounded).
 * {@code @AutoConfigureObservability} re-enables the Prometheus registry that Spring Boot switches off in tests.
 */
@org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
class MonitoringIntegrationTest extends ReliabilityTestBase {

    private static final List<String> REQUIRED = List.of(
            "payment_requests_total", "payment_success_total", "payment_failure_total", "payment_pending_total",
            "payment_processing_duration_seconds_count", "payment_processing_duration_seconds_bucket",
            "reconciliation_runs_total", "reconciliation_resolved_total", "duplicate_requests_prevented_total",
            "webhook_delivery_failures_total");

    private String scrape() throws Exception {
        return mvc.perform(get("/actuator/prometheus")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void allRequiredBusinessMetricsAreExposedFromStartupWithBoundedLabels() throws Exception {
        Party payer = party("mo1");
        Party payee = party("mo1p");
        String k = key();
        pay(payer, payee.vpa(), 500, k);
        pay(payer, payee.vpa(), 500, k);        // idempotent replay -> duplicate prevented
        String text = scrape();

        for (String name : REQUIRED) {
            assertThat(text).as(name).containsPattern("(?m)^" + Pattern.quote(name) + "[{ ]");
        }
        assertThat(sample(text, "payment_requests_total")).isGreaterThanOrEqualTo(1.0);
        assertThat(sample(text, "payment_success_total")).isGreaterThanOrEqualTo(1.0);
        assertThat(sample(text, "duplicate_requests_prevented_total")).isGreaterThanOrEqualTo(1.0);

        // Cardinality: never an id of a user, payment, bank reference or VPA in a time series.
        assertThat(text).doesNotContainPattern("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                .doesNotContain("PSB").doesNotContain("@paysure").doesNotContainPattern("(?i)(userid|paymentid|user_id|payment_id|reference)=");
        // Our business metrics carry only the application label plus a few bounded tags.
        for (String line : text.lines().filter(l -> l.matches("^(payment_|reconciliation_|webhook_|outbox_|risk_|ratelimit_|events_).*")).toList()) {
            assertThat(line).as(line).matches("^[a-z_]+(\\{.*\\})? [-0-9.eE+NaInf]+$");
            if (line.contains("{")) {
                String labels = line.substring(line.indexOf('{') + 1, line.indexOf('}'));
                for (String pair : labels.split(",")) {
                    assertThat(pair.split("=")[0]).as(line).isIn("application", "outcome", "level", "policy", "result", "le", "quantile");
                }
            }
        }
    }

    @Test
    void summaryReportsRealPaymentsPipelineRiskAndDependencies() throws Exception {
        Party lab = adminParty();
        Party payee = party("mo2p");
        pay(lab, payee.vpa(), 500, key());
        pay(lab, payee.vpa(), 500, key(), scenario("TIMEOUT_AFTER_COMMIT"));
        pay(lab, payee.vpa(), SEED * 9, key());   // fails: insufficient funds
        reconcile();

        String json = mvc.perform(get("/api/admin/monitoring/summary").header("Authorization", lab.bearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode s = readJson(json);
        assertThat(s.at("/payments/total").asLong()).isGreaterThanOrEqualTo(3);
        assertThat(s.at("/payments/success").asLong()).isGreaterThanOrEqualTo(2);
        assertThat(s.at("/payments/failed").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(s.at("/payments/successRate").asDouble()).isBetween(0.0, 1.0);
        assertThat(s.at("/payments/p95Ms").asLong()).isGreaterThanOrEqualTo(s.at("/payments/p50Ms").asLong());
        assertThat(s.at("/countersSinceProcessStart/paymentRequests").asDouble()).isGreaterThanOrEqualTo(3.0);
        assertThat(s.at("/countersSinceProcessStart/reconciliationRuns").asDouble()).isGreaterThanOrEqualTo(1.0);
        assertThat(s.has("pipeline")).isTrue();
        assertThat(s.at("/pipeline/outboxDead").isNumber()).isTrue();
        assertThat(s.at("/risk/open").isNumber()).isTrue();

        List<String> names = s.get("dependencies").findValuesAsText("name");
        assertThat(names).contains("PostgreSQL", "Redis", "Simulated bank status API", "Reconciliation worker");
        assertThat(names).anyMatch(n -> n.startsWith("Kafka (inferred"));
        JsonNode postgres = s.get("dependencies").get(0);
        assertThat(postgres.get("status").asText()).isEqualTo("UP");
        // Redis is not running in this context: reported honestly as degraded, not as UP.
        JsonNode redis = s.get("dependencies").findParents("name").stream().filter(n -> "Redis".equals(n.get("name").asText())).findFirst().orElseThrow();
        assertThat(redis.get("status").asText()).isEqualTo("DEGRADED");
    }

    @Test
    void seriesHasThirtyOrderedOneMinuteBucketsAndCountsRecentPayments() throws Exception {
        Party lab = adminParty();
        Party payee = party("mo3p");
        pay(lab, payee.vpa(), 500, key());
        pay(lab, payee.vpa(), 500, key(), scenario("TIMEOUT_AFTER_COMMIT"));

        JsonNode s = readJson(mvc.perform(get("/api/admin/monitoring/series").header("Authorization", lab.bearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        for (String key : List.of("payments", "webhooks", "reconciliation")) {
            assertThat(s.get(key)).as(key).hasSize(30);
            List<String> times = s.get(key).findValuesAsText("t");
            assertThat(times).isSorted();
        }
        long created = 0;
        long unfinished = 0;
        for (JsonNode b : s.get("payments")) {
            created += b.get("created").asLong();
            unfinished += b.get("unfinished").asLong();
            assertThat(b.get("success").asLong() + b.get("failed").asLong() + b.get("unfinished").asLong()).isEqualTo(b.get("created").asLong());
        }
        assertThat(created).isGreaterThanOrEqualTo(2);
        assertThat(unfinished).isGreaterThanOrEqualTo(1);
    }

    @Test
    void monitoringIsAdminOnly() throws Exception {
        Party user = party("mo4");
        mvc.perform(get("/api/admin/monitoring/summary").header("Authorization", user.bearer())).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/monitoring/series").header("Authorization", user.bearer())).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/monitoring/summary")).andExpect(status().isUnauthorized());
    }

    private static double sample(String text, String metric) {
        return text.lines().filter(l -> l.startsWith(metric + "{") || l.startsWith(metric + " "))
                .mapToDouble(l -> Double.parseDouble(l.substring(l.lastIndexOf(' ') + 1))).sum();
    }
}
