package com.paysure.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.fasterxml.jackson.databind.JsonNode;
import com.paysure.events.EventPublisher.Message;
import com.paysure.support.EventsTestBase;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/** Transactional outbox (mandatory case 20: publisher failure and recovery) and its guarantees. */
class OutboxIntegrationTest extends EventsTestBase {

    @Autowired
    OutboxService outboxService;
    @Autowired
    TransactionTemplate tx;

    private List<Message> publishedFor(String aggregateId) {
        return recorder.published().stream().filter(m -> m.key().equals(aggregateId)).toList();
    }

    @Test
    void paymentEventsAreWrittenInTheSameTransactionAndCarryTheBusinessFacts() throws Exception {
        Party payer = party("ob1");
        Party payee = party("ob1p");
        JsonNode p = body(pay(payer, payee.vpa(), 25_050, key()));
        String id = p.get("id").asText();

        List<Map<String, Object>> rows = outboxRows(id);
        assertThat(outboxTypes(id)).containsExactly("payment.created", "payment.completed");
        assertThat(rows).allSatisfy(r -> assertThat(r.get("status")).isEqualTo("PENDING"));
        assertThat(rows.get(0).get("topic")).isEqualTo("paysure.payment.created");
        assertThat(rows.get(1).get("topic")).isEqualTo("paysure.payment.completed");

        JsonNode done = envelope(rows.get(1));
        assertThat(done.get("eventId").asText()).isEqualTo(eventId(rows.get(1)).toString());
        assertThat(done.get("aggregateId").asText()).isEqualTo(id);
        assertThat(done.get("aggregateType").asText()).isEqualTo("PAYMENT");
        assertThat(Instant.parse(done.get("occurredAt").asText())).isBeforeOrEqualTo(Instant.now());
        assertThat(done.at("/data/status").asText()).isEqualTo("SUCCESS");
        assertThat(done.at("/data/amountPaise").asLong()).isEqualTo(25_050);
        assertThat(done.at("/data/payerVpa").asText()).isEqualTo(payer.vpa());
        assertThat(done.at("/data/payeeVpa").asText()).isEqualTo(payee.vpa());
        assertThat(done.at("/data/bankReference").asText()).startsWith("PSB");
        assertThat(done.at("/data/resolvedBy").asText()).isEqualTo("BANK_RESPONSE");
        // Never leaks internals such as user ids or secrets.
        assertThat(done.toString()).doesNotContain(payer.user().id().toString());
    }

    @Test
    void anEventRowRollsBackWithTheBusinessTransactionAndRequiresOne() {
        UUID aggregate = UUID.randomUUID();
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            outboxService.record(EventTypes.PAYMENT_CREATED, "PAYMENT", aggregate, Map.of("x", 1));
            throw new IllegalStateException("business failure after the event was recorded");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(outboxRows(aggregate.toString())).isEmpty();

        assertThatThrownBy(() -> outboxService.record(EventTypes.PAYMENT_CREATED, "PAYMENT", aggregate, Map.of()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void refusedAndAmbiguousPaymentsEmitTheRightEvents() throws Exception {
        Party lab = adminParty();
        Party payee = party("ob2p");

        String poor = body(pay(lab, payee.vpa(), SEED * 5, key())).get("id").asText();
        assertThat(outboxTypes(poor)).containsExactly("payment.created", "payment.failed");
        assertThat(envelope(outboxRows(poor).get(1)).at("/data/failureCode").asText()).isEqualTo("INSUFFICIENT_FUNDS");

        String pending = body(pay(lab, payee.vpa(), 50_000, key(), scenario("TIMEOUT_AFTER_COMMIT"))).get("id").asText();
        assertThat(outboxTypes(pending)).as("PENDING is not final, so only 'created'").containsExactly("payment.created");

        reconcile();
        assertThat(outboxTypes(pending)).containsExactly("payment.created", "payment.completed");
        assertThat(envelope(outboxRows(pending).get(1)).at("/data/resolvedBy").asText()).isEqualTo("RECONCILIATION");
    }

    @Test
    void reconciliationRunsPublishAnEventAtomicallyWithTheirRecord() throws Exception {
        Party lab = adminParty();
        Party payee = party("ob3p");
        body(pay(lab, payee.vpa(), 50_000, key(), scenario("TIMEOUT_AFTER_COMMIT")));
        var run = reconcile();

        List<Map<String, Object>> rows = outboxRows(run.id().toString());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("event_type")).isEqualTo("reconciliation.completed");
        JsonNode data = envelope(rows.get(0)).get("data");
        assertThat(data.get("runId").asText()).isEqualTo(run.id().toString());
        assertThat(data.get("resolved").asInt()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void case20_publisherFailureLeavesBusinessDataIntactAndRecoveryPublishesEachEventOnceInOrder() throws Exception {
        Party payer = party("ob4");
        Party payee = party("ob4p");
        recorder.setFailing(true);

        String id = body(pay(payer, payee.vpa(), 10_000, key())).get("id").asText();
        // The payment is fully committed even though the broker is down.
        assertThat(paymentStatus(id)).isEqualTo("SUCCESS");
        assertThat(balance(payer.accountId())).isEqualTo(SEED - 10_000);

        OutboxPublisher.Result failed = outbox.publishPending();
        assertThat(failed.published()).isZero();
        assertThat(failed.failed()).isEqualTo(1);
        assertThat(recorder.published()).isEmpty();
        List<Map<String, Object>> rows = outboxRows(id);
        assertThat(rows).allSatisfy(r -> assertThat(r.get("status")).isEqualTo("PENDING"));
        assertThat(rows.get(0).get("attempts")).isEqualTo(1);
        assertThat((String) rows.get(0).get("last_error")).contains("simulated broker outage");
        assertThat(rows.get(1).get("attempts")).as("later events were not attempted: order is preserved").isEqualTo(0);
        // While paused after a failure the scheduled path does not hammer the broker.
        assertThat(outbox.publishIfDue().skipped()).isTrue();

        recorder.setFailing(false);
        OutboxPublisher.Result recovered = outbox.publishPending();
        assertThat(recovered.published()).isGreaterThanOrEqualTo(2);
        assertThat(publishedFor(id)).extracting(Message::eventType).containsExactly("payment.created", "payment.completed");
        assertThat(publishedFor(id)).extracting(Message::key).containsOnly(id);       // key = aggregate id
        assertThat(outboxRows(id)).allSatisfy(r -> assertThat(r.get("status")).isEqualTo("PUBLISHED"));
        assertThat(jdbc.queryForObject("select published_at is not null from outbox_events where id = ?",
                Boolean.class, eventId(outboxRows(id).get(0)))).isTrue();

        // Nothing is published twice.
        assertThat(outbox.publishPending().published()).isZero();
        assertThat(publishedFor(id)).hasSize(2);
        assertLedgerInvariants();
    }

    @Test
    void aPoisonEventIsDeadLetteredAfterTheLimitSoItCannotBlockTheQueue() throws Exception {
        Party payer = party("ob5");
        Party payee = party("ob5p");
        String id = body(pay(payer, payee.vpa(), 10_000, key())).get("id").asText();
        List<Map<String, Object>> rows = outboxRows(id);
        UUID first = eventId(rows.get(0));
        UUID second = eventId(rows.get(1));
        recorder.failFor(first);

        outbox.publishPending();                       // attempt 1: head fails, nothing behind it is published
        outbox.publishPending();                       // attempt 2
        assertThat(recorder.published()).isEmpty();
        outbox.publishPending();                       // attempt 3 = max-attempts: DEAD, the rest proceeds

        assertThat(jdbc.queryForObject("select status from outbox_events where id = ?", String.class, first)).isEqualTo("DEAD");
        assertThat(jdbc.queryForObject("select status from outbox_events where id = ?", String.class, second)).isEqualTo("PUBLISHED");
        assertThat(publishedFor(id)).extracting(Message::eventId).containsExactly(second);
    }

    @Test
    void concurrentPublishersTakeTurnsSoEveryEventIsPublishedOnceAndInOrder() throws Exception {
        Party payer = party("ob6");
        Party payee = party("ob6p");
        List<String> ids = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) {
            ids.add(body(pay(payer, payee.vpa(), 1_000, key())).get("id").asText());
        }

        runConcurrently(4, () -> outbox.publishPending());
        for (int i = 0; i < 20 && outbox.pendingCount() > 0; i++) {
            outbox.publishPending();
        }

        List<UUID> seen = recorder.published().stream().map(Message::eventId).toList();
        assertThat(seen).doesNotHaveDuplicates();
        for (String id : ids) {
            assertThat(publishedFor(id)).extracting(Message::eventType).containsExactly("payment.created", "payment.completed");
        }
        // Global publication order equals outbox sequence order.
        List<UUID> bySeq = jdbc.queryForList("select id from outbox_events where status = 'PUBLISHED' order by seq", UUID.class);
        assertThat(bySeq).containsSubsequence(seen);
    }
}
