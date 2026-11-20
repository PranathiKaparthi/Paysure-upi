package com.paysure.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.paysure.notification.WebhookTransport.Result;
import com.paysure.support.EventsTestBase;
import com.paysure.support.RecordingWebhookTransport.Call;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/** Mandatory cases 21 (webhook retries) and 22 (invalid signature), plus dead-lettering, SSRF and secrets. */
class WebhookDeliveryIntegrationTest extends EventsTestBase {

    @Autowired
    WebhookDeliveryWorker worker;
    @Autowired
    NotificationEventHandler handler;
    @Autowired
    WebhookEndpointService endpoints;
    @Autowired
    WebhookProperties props;
    @Autowired
    MeterRegistry registry;

    private static final String HOOK = "http://localhost:9/hook";

    private record Setup(Party payer, Party payee, UUID endpointId, String secret, String paymentId) {
    }

    /** A payer with one endpoint, one payment, and the payment's events handed to the consumer (as Kafka would). */
    private Setup setup(String prefix, String url) throws Exception {
        Party payer = party(prefix);
        Party payee = party(prefix + "p");
        var created = endpoints.create(payer.user().id(), url, List.of("payment.completed"), "test");
        String id = body(pay(payer, payee.vpa(), 5_000, key())).get("id").asText();
        for (Map<String, Object> row : outboxRows(id)) {
            handler.handle((String) row.get("payload"));
        }
        return new Setup(payer, payee, created.endpoint().id(), created.secret(), id);
    }

    private Map<String, Object> delivery(UUID endpointId) {
        return jdbc.queryForMap("select * from webhook_deliveries where endpoint_id = ?", endpointId);
    }

    private List<String> attemptOutcomes(Object deliveryId) {
        return jdbc.queryForList("select outcome from webhook_delivery_attempts where delivery_id = ? order by attempt_no", String.class, deliveryId);
    }

    @Test
    void case21_failedDeliveriesAreRetriedWithBackoffAndEventuallySucceedWithTheSameSignedBody() throws Exception {
        Setup s = setup("wd1", HOOK);
        transport.script(new Result(500, null), new Result(null, "ConnectException: refused"), new Result(200, null));
        Instant base = Instant.now();

        WebhookDeliveryWorker.Result r1 = worker.deliverDue(base);
        assertThat(r1.retrying()).isEqualTo(1);
        Map<String, Object> d = delivery(s.endpointId());
        assertThat(d.get("status")).isEqualTo("RETRYING");
        assertThat(d.get("attempts")).isEqualTo(1);
        assertThat(d.get("last_status_code")).isEqualTo(500);
        assertThat((String) d.get("last_error")).isEqualTo("HTTP 500");

        assertThat(worker.deliverDue(base).attempted()).as("not due yet: backoff has not elapsed").isZero();

        WebhookDeliveryWorker.Result r2 = worker.deliverDue(base.plusSeconds(3));
        assertThat(r2.retrying()).isEqualTo(1);
        assertThat(delivery(s.endpointId()).get("last_status_code")).isNull();
        assertThat((String) delivery(s.endpointId()).get("last_error")).contains("ConnectException");

        WebhookDeliveryWorker.Result r3 = worker.deliverDue(base.plusSeconds(9));
        assertThat(r3.succeeded()).isEqualTo(1);
        d = delivery(s.endpointId());
        assertThat(d.get("status")).isEqualTo("SUCCESS");
        assertThat(d.get("attempts")).isEqualTo(3);
        assertThat(d.get("delivered_at")).isNotNull();
        assertThat(attemptOutcomes(d.get("id"))).containsExactly("RETRY", "RETRY", "SUCCESS");

        // Every attempt sent the SAME event id and the SAME bytes, each with a fresh, valid signature.
        List<Call> calls = transport.callsTo("/hook");
        assertThat(calls).hasSize(3);
        assertThat(calls).extracting(Call::body).containsOnly(calls.get(0).body());
        assertThat(calls).extracting(c -> c.headers().get(WebhookSigner.EVENT_ID_HEADER)).containsOnly(d.get("event_id").toString());
        for (Call c : calls) {
            assertThat(WebhookSigner.verify(s.secret(), c.headers().get(WebhookSigner.TIMESTAMP_HEADER),
                    c.headers().get(WebhookSigner.SIGNATURE_HEADER), c.body(), props.replayTolerance(), Instant.now()))
                    .isEqualTo(WebhookSigner.Result.VALID);
            assertThat(c.headers()).containsKeys(WebhookSigner.DELIVERY_ID_HEADER, WebhookSigner.EVENT_TYPE_HEADER,
                    WebhookSigner.ENDPOINT_ID_HEADER);
        }
        // Already delivered: never sent again.
        assertThat(worker.deliverDue(base.plusSeconds(100)).attempted()).isZero();
        assertThat(transport.callsTo("/hook")).hasSize(3);
    }

    @Test
    void afterTheMaximumAttemptsADeliveryIsDeadLetteredAndCanBeRetriedByItsOwner() throws Exception {
        Setup s = setup("wd2", HOOK);
        transport.respondWith(new Result(503, null));
        double failuresBefore = registry.counter("webhook.delivery.failures").count();
        Instant base = Instant.now();

        worker.deliverDue(base);
        worker.deliverDue(base.plusSeconds(3));
        WebhookDeliveryWorker.Result last = worker.deliverDue(base.plusSeconds(10));

        assertThat(last.dead()).isEqualTo(1);
        Map<String, Object> d = delivery(s.endpointId());
        assertThat(d.get("status")).isEqualTo("DEAD");
        assertThat(d.get("attempts")).isEqualTo(3);
        assertThat(attemptOutcomes(d.get("id"))).containsExactly("RETRY", "RETRY", "DEAD");
        assertThat(registry.counter("webhook.delivery.failures").count() - failuresBefore).isEqualTo(3.0);
        assertThat(worker.deliverDue(base.plusSeconds(1000)).attempted()).as("dead letters are not retried automatically").isZero();

        // The owner re-queues it; the receiver has recovered.
        transport.respondWith(new Result(200, null));
        mvc.perform(post("/api/webhooks/deliveries/{id}/retry", d.get("id")).header("Authorization", s.payee().bearer()))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/webhooks/deliveries/{id}/retry", d.get("id")).header("Authorization", s.payer().bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING")).andExpect(jsonPath("$.attempts").value(0));
        assertThat(worker.deliverDue(Instant.now().plusSeconds(1)).succeeded()).isGreaterThanOrEqualTo(1);
        assertThat(delivery(s.endpointId()).get("status")).isEqualTo("SUCCESS");
        mvc.perform(post("/api/webhooks/deliveries/{id}/retry", d.get("id")).header("Authorization", s.payer().bearer()))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("DELIVERY_NOT_DEAD"));
    }

    @Test
    void acceptanceF_aFlakyReceiverIsRetriedAndTheBusinessEffectHappensExactlyOnce() throws Exception {
        // The built-in receiver fails the first two calls, then processes; PaySure's worker talks to it through MockMvc.
        String url = "http://localhost:8080/webhook-sandbox/receiver?fail=2";
        Setup s = setup("wd3", url);
        transport.handleWith(call -> {
            try {
                var req = post("/webhook-sandbox/receiver?" + call.url().getQuery()).contentType(MediaType.APPLICATION_JSON).content(call.body());
                call.headers().forEach(req::header);
                return new Result(mvc.perform(req).andReturn().getResponse().getStatus(), null);
            } catch (Exception e) {
                return new Result(null, e.toString());
            }
        });
        Instant base = Instant.now();
        worker.deliverDue(base);
        worker.deliverDue(base.plusSeconds(3));
        worker.deliverDue(base.plusSeconds(9));

        Map<String, Object> d = delivery(s.endpointId());
        assertThat(d.get("status")).isEqualTo("SUCCESS");
        assertThat(d.get("attempts")).isEqualTo(3);
        List<Integer> codes = jdbc.queryForList("select status_code from webhook_delivery_attempts where delivery_id = ? order by attempt_no",
                Integer.class, d.get("id"));
        assertThat(codes).containsExactly(500, 500, 200);

        Map<String, Object> receipt = jdbc.queryForMap("select * from webhook_receipts where endpoint_id = ?", s.endpointId());
        assertThat(receipt.get("calls")).isEqualTo(3);
        assertThat(receipt.get("processed_at")).as("business processing happened once").isNotNull();
        assertThat(receipt.get("last_result")).isEqualTo("PROCESSED");

        // A late duplicate delivery of the same event (e.g. a re-claimed lease) is absorbed by the receiver.
        Call original = transport.calls().get(0);
        var dup = post("/webhook-sandbox/receiver?fail=2").contentType(MediaType.APPLICATION_JSON).content(original.body());
        original.headers().forEach(dup::header);
        // (the timestamp is still inside the replay window; the event id makes it idempotent)
        mvc.perform(dup).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("duplicate ignored"));
        receipt = jdbc.queryForMap("select * from webhook_receipts where endpoint_id = ?", s.endpointId());
        assertThat(receipt.get("calls")).isEqualTo(4);
        assertThat(jdbc.queryForObject("select count(*) from webhook_receipts where endpoint_id = ? and processed_at is not null",
                Long.class, s.endpointId())).isEqualTo(1);
        // Webhooks only report; no money moved because of any retry.
        assertThat(transferJournals(s.paymentId())).isEqualTo(1);
        assertLedgerInvariants();
    }

    // ---- case 22: invalid signatures ------------------------------------------------------------------------

    private org.springframework.test.web.servlet.ResultActions callReceiver(UUID endpoint, UUID eventId, String body, String ts, String sig)
            throws Exception {
        var req = post("/webhook-sandbox/receiver").contentType(MediaType.APPLICATION_JSON).content(body);
        if (endpoint != null) {
            req = req.header(WebhookSigner.ENDPOINT_ID_HEADER, endpoint.toString());
        }
        if (eventId != null) {
            req = req.header(WebhookSigner.EVENT_ID_HEADER, eventId.toString());
        }
        if (ts != null) {
            req = req.header(WebhookSigner.TIMESTAMP_HEADER, ts);
        }
        if (sig != null) {
            req = req.header(WebhookSigner.SIGNATURE_HEADER, sig);
        }
        return mvc.perform(req.header(WebhookSigner.EVENT_TYPE_HEADER, "payment.completed"));
    }

    private long receipts(UUID endpoint) {
        return jdbc.queryForObject("select count(*) from webhook_receipts where endpoint_id = ?", Long.class, endpoint);
    }

    @Test
    void case22_theReceiverRejectsBadSignaturesStaleTimestampsAndMismatchedBodiesWithoutProcessing() throws Exception {
        Party owner = party("wd4");
        var created = endpoints.create(owner.user().id(), HOOK, List.of("*"), "receiver test");
        UUID ep = created.endpoint().id();
        String secret = created.secret();
        UUID eventId = UUID.randomUUID();
        String body = "{\"eventId\":\"" + eventId + "\",\"eventType\":\"payment.completed\",\"data\":{\"amountPaise\":100}}";
        long now = Instant.now().getEpochSecond();
        String good = WebhookSigner.sign(secret, now, body);

        // Tampered body (signature was computed over different bytes).
        callReceiver(ep, eventId, body.replace("100", "999"), Long.toString(now), good).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(org.hamcrest.Matchers.containsString("BAD_SIGNATURE")));
        // Signed with the wrong secret.
        callReceiver(ep, eventId, body, Long.toString(now), WebhookSigner.sign("whsec_wrong", now, body)).andExpect(status().isUnauthorized());
        // Garbage / missing / wrong-scheme signature headers.
        callReceiver(ep, eventId, body, Long.toString(now), "v1=deadbeef").andExpect(status().isUnauthorized());
        callReceiver(ep, eventId, body, Long.toString(now), null).andExpect(status().isUnauthorized());
        callReceiver(ep, eventId, body, null, good).andExpect(status().isUnauthorized());
        callReceiver(ep, eventId, body, Long.toString(now), good.replace("v1=", "sha256=")).andExpect(status().isUnauthorized());
        // A genuine old signature replayed outside the window.
        long old = now - 600;
        callReceiver(ep, eventId, body, Long.toString(old), WebhookSigner.sign(secret, old, body)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(org.hamcrest.Matchers.containsString("TIMESTAMP_OUT_OF_WINDOW")));
        // Unknown endpoint and missing identification: same 401, no information about what exists.
        callReceiver(UUID.randomUUID(), eventId, body, Long.toString(now), good).andExpect(status().isUnauthorized());
        callReceiver(null, eventId, body, Long.toString(now), good).andExpect(status().isUnauthorized());
        // Header event id that does not match the signed body.
        callReceiver(ep, UUID.randomUUID(), body, Long.toString(now), good).andExpect(status().isBadRequest());

        assertThat(receipts(ep)).as("nothing was recorded or processed for any rejected request").isZero();

        // And the genuine request is accepted, once.
        callReceiver(ep, eventId, body, Long.toString(now), good).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("processed"));
        callReceiver(ep, eventId, body, Long.toString(now), good).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("duplicate ignored"));
        assertThat(receipts(ep)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select calls from webhook_receipts where endpoint_id = ?", Integer.class, ep)).isEqualTo(2);
    }

    // ---- delivery-time safety ------------------------------------------------------------------------------

    @Test
    void anEndpointThatBecomesBlockedAfterRegistrationIsDeadLetteredWithoutAnyRequest() throws Exception {
        Setup s = setup("wd5", HOOK);
        // e.g. the registered hostname was later re-pointed at an internal address (checked again right before sending)
        jdbc.update("update webhook_endpoints set url = 'https://169.254.169.254/latest/meta-data/' where id = ?", s.endpointId());

        assertThat(worker.deliverDue(Instant.now()).dead()).isEqualTo(1);
        Map<String, Object> d = delivery(s.endpointId());
        assertThat(d.get("status")).isEqualTo("DEAD");
        assertThat((String) d.get("last_error")).contains("SSRF");
        assertThat(transport.calls()).isEmpty();
    }

    @Test
    void aDisabledEndpointIsNotCalled() throws Exception {
        Setup s = setup("wd6", HOOK);
        endpoints.setEnabled(s.payer().user().id(), s.endpointId(), false);
        assertThat(worker.deliverDue(Instant.now()).dead()).isEqualTo(1);
        assertThat((String) delivery(s.endpointId()).get("last_error")).contains("disabled");
        assertThat(transport.calls()).isEmpty();
    }

    @Test
    void rotatingTheSecretSignsQueuedDeliveriesWithTheNewSecretOnly() throws Exception {
        Setup s = setup("wd7", HOOK);
        String newSecret = endpoints.rotateSecret(s.payer().user().id(), s.endpointId()).secret();
        assertThat(newSecret).isNotEqualTo(s.secret());

        worker.deliverDue(Instant.now());
        Call c = transport.callsTo("/hook").get(0);
        Instant now = Instant.now();
        assertThat(WebhookSigner.verify(newSecret, c.headers().get(WebhookSigner.TIMESTAMP_HEADER),
                c.headers().get(WebhookSigner.SIGNATURE_HEADER), c.body(), Duration.ofMinutes(5), now)).isEqualTo(WebhookSigner.Result.VALID);
        assertThat(WebhookSigner.verify(s.secret(), c.headers().get(WebhookSigner.TIMESTAMP_HEADER),
                c.headers().get(WebhookSigner.SIGNATURE_HEADER), c.body(), Duration.ofMinutes(5), now)).isEqualTo(WebhookSigner.Result.BAD_SIGNATURE);
    }

    @Test
    void concurrentWorkersNeverSendTheSameDeliveryTwiceAtOnce() throws Exception {
        Setup s = setup("wd8", HOOK);
        List<WebhookDeliveryWorker.Result> results = runConcurrently(4, () -> worker.deliverDue(Instant.now()));
        assertThat(results.stream().mapToInt(WebhookDeliveryWorker.Result::succeeded).sum()).isGreaterThanOrEqualTo(1);
        assertThat(transport.callsTo("/hook")).as("one claim wins; the others skip the locked row").hasSize(1);
        assertThat(delivery(s.endpointId()).get("attempts")).isEqualTo(1);
    }

    // ---- management API -------------------------------------------------------------------------------------

    @Test
    void endpointApiBlocksSsrfTargetsHidesSecretsAndEnforcesOwnershipAndLimits() throws Exception {
        Party owner = party("wd9");
        Party other = party("wd9o");
        for (String bad : List.of("https://127.0.0.1/x", "https://10.1.2.3/x", "https://169.254.169.254/latest", "https://[::1]/x",
                "https://192.168.0.5/x", "ftp://93.184.216.34/x", "https://u:p@93.184.216.34/x", "http://93.184.216.34/x")) {
            mvc.perform(post("/api/webhooks/endpoints").header("Authorization", owner.bearer()).contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("url", bad))))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("WEBHOOK_URL_BLOCKED"));
        }
        mvc.perform(post("/api/webhooks/endpoints").header("Authorization", owner.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("url", "https://93.184.216.34/x", "eventTypes", List.of("nope.type")))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_EVENT_TYPE"));

        JsonNode created = body(mvc.perform(post("/api/webhooks/endpoints").header("Authorization", owner.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("url", "https://93.184.216.34/x", "eventTypes", List.of("payment.completed"),
                                "description", "prod"))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.secret").value(org.hamcrest.Matchers.startsWith("whsec_"))));
        String id = created.at("/endpoint/id").asText();

        // The secret is shown once; listing never includes it, and it is not stored in the database.
        String list = mvc.perform(get("/api/webhooks/endpoints").header("Authorization", owner.bearer())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(list).doesNotContain("whsec_").doesNotContain("\"secret\"");
        assertThat(jdbc.queryForList("select column_name from information_schema.columns where table_name = 'webhook_endpoints'", String.class))
                .noneMatch(c -> c.equals("secret"));

        // Another user cannot see or change it.
        mvc.perform(post("/api/webhooks/endpoints/{id}/disable", id).header("Authorization", other.bearer())).andExpect(status().isNotFound());
        mvc.perform(post("/api/webhooks/endpoints/{id}/rotate-secret", id).header("Authorization", other.bearer())).andExpect(status().isNotFound());
        mvc.perform(get("/api/webhooks/endpoints").header("Authorization", other.bearer())).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(post("/api/webhooks/endpoints/{id}/disable", id).header("Authorization", owner.bearer()))
                .andExpect(jsonPath("$.enabled").value(false));

        // Rotation returns a different secret.
        String rotated = body(mvc.perform(post("/api/webhooks/endpoints/{id}/rotate-secret", id).header("Authorization", owner.bearer())))
                .get("secret").asText();
        assertThat(rotated).isNotEqualTo(created.get("secret").asText());

        // Limit of 5 per user.
        for (int i = 0; i < 4; i++) {
            endpoints.create(owner.user().id(), "https://93.184.216.34/e" + i, List.of("*"), null);
        }
        mvc.perform(post("/api/webhooks/endpoints").header("Authorization", owner.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("url", "https://93.184.216.34/e9"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("WEBHOOK_LIMIT_REACHED"));
        mvc.perform(get("/api/webhooks/endpoints")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/webhooks/config").header("Authorization", owner.bearer())).andExpect(status().isOk())
                .andExpect(jsonPath("$.eventTypes.length()").value(5)).andExpect(jsonPath("$.replayToleranceSeconds").value(300));
    }

    @Test
    void deliveryLogIsScopedToTheOwnerAndShowsAttempts() throws Exception {
        Setup s = setup("wd10", HOOK);
        transport.script(new Result(500, null), new Result(200, null));
        Instant base = Instant.now();
        worker.deliverDue(base);
        worker.deliverDue(base.plusSeconds(3));

        mvc.perform(get("/api/webhooks/deliveries?endpointId=" + s.endpointId()).header("Authorization", s.payer().bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].status").value("SUCCESS"))
                .andExpect(jsonPath("$[0].attempts").value(2)).andExpect(jsonPath("$[0].eventType").value("payment.completed"));
        String id = delivery(s.endpointId()).get("id").toString();
        mvc.perform(get("/api/webhooks/deliveries/{id}", id).header("Authorization", s.payer().bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.attempts.length()").value(2))
                .andExpect(jsonPath("$.attempts[0].statusCode").value(500)).andExpect(jsonPath("$.attempts[1].outcome").value("SUCCESS"))
                .andExpect(jsonPath("$.payload").isNotEmpty());
        mvc.perform(get("/api/webhooks/deliveries/{id}", id).header("Authorization", s.payee().bearer())).andExpect(status().isNotFound());
        mvc.perform(get("/api/webhooks/deliveries?status=DEAD&endpointId=" + s.endpointId()).header("Authorization", s.payer().bearer()))
                .andExpect(jsonPath("$.length()").value(0));
    }
}
