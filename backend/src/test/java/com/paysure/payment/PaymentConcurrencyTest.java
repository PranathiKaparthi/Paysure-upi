package com.paysure.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.paysure.support.PaymentTestBase;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Mandatory cases 8, 9 and 25 under real concurrency against PostgreSQL. */
class PaymentConcurrencyTest extends PaymentTestBase {

    @Test
    void case8_tenIdenticalConcurrentRequestsProduceExactlyOneTransfer() throws Exception {
        Party payer = party("burst");
        Party payee = party("burstp");
        String sharedKey = key();

        List<JsonNode> responses = runConcurrently(10, () -> {
            var result = pay(payer, payee.vpa(), 12_345, sharedKey).andReturn().getResponse();
            assertThat(result.getStatus()).isIn(200, 201);
            return readJson(result.getContentAsString());
        });

        Set<String> ids = responses.stream().map(r -> r.get("id").asText()).collect(Collectors.toSet());
        assertThat(ids).as("all ten callers see the same logical payment").hasSize(1);
        String id = ids.iterator().next();
        assertThat(paymentsFrom(payer.accountId())).isEqualTo(1);
        assertThat(transferJournals(id)).isEqualTo(1);
        Long attempts = jdbc.queryForObject("select count(*) from payment_attempts where payment_id = ?::uuid", Long.class, id);
        assertThat(attempts).as("submitted to the bank exactly once").isEqualTo(1);
        assertThat(balance(payer.accountId())).isEqualTo(SEED - 12_345);
        assertThat(balance(payee.accountId())).isEqualTo(SEED + 12_345);
        assertLedgerInvariants();
    }

    @Test
    void case9_twoCompetingPaymentsAgainstOneBalance_onlyOneCanWin() throws Exception {
        Party payer = party("compete");
        Party payee1 = party("competea");
        Party payee2 = party("competeb");
        // ₹6,000 + ₹6,000 requested from ₹10,000.
        AtomicInteger turn = new AtomicInteger();
        List<JsonNode> both = runConcurrently(2, () -> {
            Party payee = turn.getAndIncrement() == 0 ? payee1 : payee2;
            return body(pay(payer, payee.vpa(), 600_000, key()));
        });

        assertThat(both).extracting(r -> r.get("status").asText()).containsExactlyInAnyOrder("SUCCESS", "FAILED");
        assertThat(both).filteredOn(r -> r.get("status").asText().equals("FAILED"))
                .extracting(r -> r.get("failureCode").asText()).containsExactly("INSUFFICIENT_FUNDS");
        assertThat(balance(payer.accountId())).isEqualTo(SEED - 600_000);
        assertThat(balance(payee1.accountId()) + balance(payee2.accountId())).isEqualTo(2 * SEED + 600_000);
        assertLedgerInvariants();
    }

    @Test
    void case25_manyConcurrentPaymentsNeverDoubleDebitOrOverspend() throws Exception {
        Party payer = party("storm");
        Party payee = party("stormp");
        // 25 distinct payments of ₹500 against ₹10,000: exactly 20 fit.
        List<JsonNode> results = runConcurrently(25, () -> body(pay(payer, payee.vpa(), 50_000, key())));

        long ok = results.stream().filter(r -> r.get("status").asText().equals("SUCCESS")).count();
        long failed = results.stream().filter(r -> r.get("status").asText().equals("FAILED")).count();
        assertThat(ok).isEqualTo(20);
        assertThat(failed).isEqualTo(5);
        assertThat(balance(payer.accountId())).isZero();
        assertThat(balance(payee.accountId())).isEqualTo(SEED + 20 * 50_000L);
        assertLedgerInvariants();
    }
}
