package com.paysure.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.paysure.bank.simulator.SimulatedBank;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** Helpers for payment tests: funded parties, payment calls, and driving the simulated bank's clock. */
public abstract class PaymentTestBase extends IntegrationTest {

    protected static final long SEED = 1_000_000L; // ₹10,000.00

    @Autowired
    protected SimulatedBank bank;

    /** A user with one funded account and one VPA. */
    public record Party(TestUser user, UUID accountId, String vpa) {
        public String bearer() {
            return user.bearer();
        }
    }

    protected Party party(String prefix) throws Exception {
        return withAccount(registerAndLogin(prefix), prefix);
    }

    protected Party adminParty() throws Exception {
        return withAccount(adminDemo(), "lab");
    }

    private Party withAccount(TestUser user, String prefix) throws Exception {
        String handle = uniqueHandle(prefix.replaceAll("[^a-z]", "x"));
        String body = mvc.perform(post("/api/accounts").header("Authorization", user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("displayName", "Main", "vpaHandle", handle))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return new Party(user, UUID.fromString(readJson(body).get("id").asText()), handle + "@paysure");
    }

    protected ResultActions pay(Party payer, String payeeVpa, long amountPaise, String key) throws Exception {
        return pay(payer, payeeVpa, amountPaise, key, null);
    }

    protected ResultActions pay(Party payer, String payeeVpa, long amountPaise, String key, Map<String, Object> simulation)
            throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("payerVpa", payer.vpa());
        body.put("payeeVpa", payeeVpa);
        body.put("amountPaise", amountPaise);
        body.put("note", "test");
        if (simulation != null) {
            body.put("simulation", simulation);
        }
        var req = post("/api/payments").header("Authorization", payer.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        if (key != null) {
            req = req.header("Idempotency-Key", key);
        }
        return mvc.perform(req);
    }

    protected JsonNode body(ResultActions result) throws Exception {
        return readJson(result.andReturn().getResponse().getContentAsString());
    }

    protected static String key() {
        return "key-" + UUID.randomUUID();
    }

    protected static Map<String, Object> scenario(String name) {
        return Map.of("scenario", name);
    }

    protected static Map<String, Object> scenario(String name, int delaySeconds, int duplicates) {
        return Map.of("scenario", name, "delaySeconds", delaySeconds, "duplicateCallbacks", duplicates);
    }

    protected JsonNode detail(Party viewer, String paymentId) throws Exception {
        return readJson(mvc.perform(get("/api/payments/{id}", paymentId).header("Authorization", viewer.bearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    protected JsonNode checkStatus(Party payer, String paymentId) throws Exception {
        return readJson(mvc.perform(post("/api/payments/{id}/check-status", paymentId).header("Authorization", payer.bearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    /** Fast-forwards the simulated bank's clock so delayed outcomes resolve and callbacks are delivered. */
    protected SimulatedBank.TickResult advanceBank(long seconds) {
        return bank.tick(Instant.now().plusSeconds(seconds));
    }

    protected long balance(UUID accountId) {
        return jdbc.queryForObject("select balance_paise from accounts where id = ?", Long.class, accountId);
    }

    protected long reserved(UUID accountId) {
        return jdbc.queryForObject("select reserved_paise from accounts where id = ?", Long.class, accountId);
    }

    protected long transferJournals(String paymentId) {
        return jdbc.queryForObject("select count(*) from ledger_journals where journal_type = 'PAYMENT_TRANSFER' and reference_id = ?::uuid",
                Long.class, paymentId);
    }

    protected long paymentsFrom(UUID payerAccountId) {
        return jdbc.queryForObject("select count(*) from payments where payer_account_id = ?", Long.class, payerAccountId);
    }

    protected static <T> List<T> runConcurrently(int threads, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
