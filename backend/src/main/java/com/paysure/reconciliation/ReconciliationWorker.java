package com.paysure.reconciliation;

import com.paysure.bank.BankContracts.TransferStatus;
import com.paysure.payment.application.PaymentFinalizer.Effect;
import com.paysure.payment.application.PaymentStatusVerifier;
import com.paysure.payment.application.PaymentStatusVerifier.Verification;
import com.paysure.payment.domain.PaymentStatus;
import com.paysure.payment.domain.TransitionSource;
import com.paysure.reconciliation.ReconciliationModel.CheckKind;
import com.paysure.reconciliation.ReconciliationModel.Outcome;
import com.paysure.reconciliation.ReconciliationModel.RunSummary;
import com.paysure.reconciliation.ReconciliationModel.TriggerType;
import com.paysure.events.EventTypes;
import com.paysure.events.OutboxService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Self-healing reconciliation. For each unfinished payment it asks the bank — by the stable reference — what
 * really happened, and applies the answer through the same exactly-once finaliser used by callbacks.
 *
 * <ul>
 *   <li><b>Safe claiming.</b> Payments are claimed with a time-limited lease using
 *       {@code UPDATE … WHERE id IN (SELECT … FOR UPDATE SKIP LOCKED) RETURNING id}. Concurrent workers never
 *       receive the same payment; a crashed worker's lease simply expires.</li>
 *   <li><b>Never re-sends.</b> Only {@link PaymentStatusVerifier} is used, which only queries.</li>
 *   <li><b>Unknown stays unknown.</b> "Still processing", "no record yet" and "bank unreachable" leave the payment
 *       PENDING; they are never turned into a guess.</li>
 *   <li><b>Escalation.</b> After N unresolved checks (or a maximum age) the payment is flagged for manual review.
 *       It stays PENDING and keeps being retried.</li>
 *   <li><b>Cross-checks.</b> Finished payments are re-verified once against the bank (conflicts are flagged, never
 *       auto-"fixed"), and global ledger/hold invariants are checked every run.</li>
 * </ul>
 */
@Service
public class ReconciliationWorker {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationWorker.class);

    private final JdbcTemplate jdbc;
    private final PaymentStatusVerifier verifier;
    private final ReconciliationProperties props;
    private final Clock clock;
    private final OutboxService outbox;
    private final TransactionTemplate tx;
    private final Counter runsCounter;
    private final Counter resolvedCounter;

    public ReconciliationWorker(JdbcTemplate jdbc, PaymentStatusVerifier verifier, ReconciliationProperties props,
                                Clock clock, MeterRegistry registry, OutboxService outbox, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.verifier = verifier;
        this.props = props;
        this.clock = clock;
        this.outbox = outbox;
        this.tx = tx;
        this.runsCounter = Counter.builder("reconciliation.runs").description("Reconciliation runs completed").register(registry);
        this.resolvedCounter = Counter.builder("reconciliation.resolved")
                .description("Payments resolved by reconciliation").register(registry);
    }

    public RunSummary run(TriggerType trigger) {
        return run(trigger, props.minAge());
    }

    /** @param minAge in-flight payments must have been unfinished for at least this long */
    public RunSummary run(TriggerType trigger, Duration minAge) {
        Instant started = clock.instant();
        UUID runId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        jdbc.update("insert into reconciliation_runs (id, trigger_type, status, started_at) values (?, ?, 'RUNNING', ?)",
                runId, trigger.name(), ts(started));
        Stats stats = new Stats();
        try {
            for (UUID paymentId : claimInFlight(owner, started, minAge)) {
                checkInFlight(runId, owner, paymentId, stats);
            }
            for (UUID paymentId : claimSettled(started)) {
                checkPostSettlement(runId, paymentId, stats);
            }
            checkLedger(runId, stats);
            Instant finished = clock.instant();
            finishRun(runId, started, finished, "COMPLETED", null, stats);
            runsCounter.increment();
        } catch (RuntimeException e) {
            log.error("Reconciliation run {} failed", runId, e);
            finishRun(runId, started, clock.instant(), "FAILED", truncate(e.toString(), 300), stats);
        }
        log.info("Reconciliation run {} ({}): checked={} resolved={} pending={} unknown={} conflicts={} ledger={} escalated={}",
                runId, trigger, stats.checked, stats.resolved, stats.stillPending, stats.unknown, stats.conflicts,
                stats.ledger, stats.escalated);
        return summary(runId).orElseThrow();
    }

    // ---- claiming ------------------------------------------------------------------------------------------

    private List<UUID> claimInFlight(UUID owner, Instant now, Duration minAge) {
        return jdbc.query("""
                        update payments set recon_lease_owner = ?, recon_lease_until = ?
                        where id in (
                            select id from payments
                            where status in ('PROCESSING', 'PENDING')
                              and updated_at <= ?
                              and (recon_lease_until is null or recon_lease_until < ?)
                            order by updated_at
                            limit ?
                            for update skip locked)
                        returning id""",
                (rs, i) -> rs.getObject("id", UUID.class),
                owner, ts(now.plus(props.lease())), ts(now.minus(minAge)), ts(now), props.batchSize());
    }

    /** Claims finished payments that have never been cross-checked; marking them checked IS the claim. */
    private List<UUID> claimSettled(Instant now) {
        return jdbc.query("""
                        update payments set last_reconciled_at = ?
                        where id in (
                            select id from payments
                            where status in ('SUCCESS', 'FAILED')
                              and last_reconciled_at is null
                              and completed_at >= ?
                            order by completed_at
                            limit ?
                            for update skip locked)
                        returning id""",
                (rs, i) -> rs.getObject("id", UUID.class),
                ts(now), ts(now.minus(props.postSettlementWindow())), props.postSettlementBatch());
    }

    // ---- in-flight payments --------------------------------------------------------------------------------

    private void checkInFlight(UUID runId, UUID owner, UUID paymentId, Stats stats) {
        Map<String, Object> before = jdbc.queryForMap("select status, created_at from payments where id = ?", paymentId);
        String statusBefore = (String) before.get("status");
        Instant created = toInstant(before.get("created_at"));
        stats.checked++;
        try {
            Verification v = verifier.verify(paymentId, TransitionSource.RECONCILIATION);
            String statusAfter = jdbc.queryForObject("select status from payments where id = ?", String.class, paymentId);
            String bank = v.bankStatus() == null ? null : v.bankStatus().status().name();
            Effect effect = v.effect();

            Outcome outcome;
            String detail;
            Long recoveryMs = null;
            if (effect == Effect.SETTLED_SUCCESS || effect == Effect.SETTLED_FAILURE) {
                outcome = Outcome.RESOLVED;
                recoveryMs = Duration.between(created, clock.instant()).toMillis();
                detail = "Bank confirmed " + bank + (effect == Effect.SETTLED_SUCCESS
                        ? "; ledger posted once and hold captured, without re-sending the transfer"
                        : "; hold released, nothing was debited");
                stats.resolved++;
                stats.recovery(recoveryMs);
                if (effect == Effect.SETTLED_SUCCESS) {
                    stats.resolvedSuccess++;
                } else {
                    stats.resolvedFailed++;
                }
                resolvedCounter.increment();
            } else if (effect == Effect.CONFLICT) {
                outcome = Outcome.CONFLICT;
                detail = "Bank status " + bank + " contradicts local status " + statusAfter + "; flagged for manual review";
                flagManualReview(paymentId, "Bank/local status conflict: " + bank + " vs " + statusAfter);
                stats.conflicts++;
            } else if (v.unreachable()) {
                outcome = Outcome.UNKNOWN;
                detail = "Bank status API unreachable; outcome still unknown, left " + statusAfter + " and will retry";
                stats.unknown++;
            } else if (PaymentStatus.valueOf(statusAfter).isTerminal()) {
                outcome = Outcome.CONSISTENT;
                detail = "Finalised concurrently (callback or manual check) before reconciliation applied anything";
            } else {
                outcome = Outcome.STILL_PENDING;
                detail = bank == null ? "No answer from bank" : "Bank says " + bank
                        + (v.bankStatus().status() == TransferStatus.NOT_FOUND ? " (within grace period)" : " (still processing)")
                        + "; left " + statusAfter + ", not guessed";
                stats.stillPending++;
            }

            if ((outcome == Outcome.STILL_PENDING || outcome == Outcome.UNKNOWN)
                    && shouldEscalate(paymentId, created)) {
                // Count it once, as escalated, instead of as pending/unknown.
                if (outcome == Outcome.UNKNOWN) {
                    stats.unknown--;
                } else {
                    stats.stillPending--;
                }
                outcome = Outcome.ESCALATED;
                detail += ". Unresolved after repeated checks: escalated to manual review (still PENDING, still retried)";
                stats.escalated++;
            }
            item(runId, paymentId, null, CheckKind.IN_FLIGHT, statusBefore, statusAfter, bank, outcome, detail, recoveryMs);
        } catch (RuntimeException e) {
            log.error("Reconciliation of payment {} failed; it stays unfinished and will be retried", paymentId, e);
            item(runId, paymentId, null, CheckKind.IN_FLIGHT, statusBefore, statusBefore, null, Outcome.UNKNOWN,
                    "Verification failed: " + truncate(e.toString(), 200), null);
            stats.unknown++;
        } finally {
            jdbc.update("""
                    update payments set recon_lease_owner = null, recon_lease_until = null,
                        recon_attempts = recon_attempts + 1, last_reconciled_at = ?
                    where id = ? and recon_lease_owner = ?""", ts(clock.instant()), paymentId, owner);
        }
    }

    /** Flags the payment for manual review; returns true only for the check that newly flags it. */
    private boolean shouldEscalate(UUID paymentId, Instant created) {
        Map<String, Object> row = jdbc.queryForMap(
                "select recon_attempts, needs_manual_review from payments where id = ?", paymentId);
        if ((Boolean) row.get("needs_manual_review")) {
            return false; // already escalated earlier
        }
        int attemptsIncludingThisOne = ((Number) row.get("recon_attempts")).intValue() + 1;
        boolean tooMany = attemptsIncludingThisOne >= props.escalateAfterAttempts();
        boolean tooOld = Duration.between(created, clock.instant()).compareTo(props.escalateAfterAge()) >= 0;
        if (tooMany || tooOld) {
            flagManualReview(paymentId, tooMany
                    ? "Unresolved after " + attemptsIncludingThisOne + " reconciliation checks"
                    : "Unresolved for " + Duration.between(created, clock.instant()).toMinutes() + " minutes");
            return true;
        }
        return false;
    }

    private void flagManualReview(UUID paymentId, String reason) {
        jdbc.update("update payments set needs_manual_review = true, manual_review_reason = ? where id = ?",
                truncate(reason, 200), paymentId);
    }

    // ---- finished payments ---------------------------------------------------------------------------------

    private void checkPostSettlement(UUID runId, UUID paymentId, Stats stats) {
        String local = jdbc.queryForObject("select status from payments where id = ?", String.class, paymentId);
        Boolean hasAttempt = jdbc.queryForObject(
                "select exists (select 1 from payment_attempts where payment_id = ?)", Boolean.class, paymentId);
        if (!Boolean.TRUE.equals(hasAttempt)) {
            return; // never reached the bank (e.g. insufficient funds locally): nothing to compare
        }
        Verification v = verifier.peek(paymentId);
        if (v.unreachable() || v.bankStatus() == null) {
            // Could not verify: put it back in the queue for a later run.
            jdbc.update("update payments set last_reconciled_at = null where id = ?", paymentId);
            return;
        }
        stats.postSettlementChecked++;
        TransferStatus bank = v.bankStatus().status();
        boolean consistent = switch (PaymentStatus.valueOf(local)) {
            case SUCCESS -> bank == TransferStatus.SUCCESS;
            case FAILED -> bank == TransferStatus.FAILED || bank == TransferStatus.NOT_FOUND;
            default -> true;
        };
        if (consistent) {
            item(runId, paymentId, null, CheckKind.POST_SETTLEMENT, local, local, bank.name(), Outcome.CONSISTENT,
                    "Local " + local + " matches the bank's record (" + bank + ")", null);
        } else {
            flagManualReview(paymentId, "Local " + local + " but bank reports " + bank);
            stats.conflicts++;
            item(runId, paymentId, null, CheckKind.POST_SETTLEMENT, local, local, bank.name(), Outcome.CONFLICT,
                    "Conflicting records: local " + local + " vs bank " + bank + ". Not auto-corrected; flagged for manual review", null);
        }
    }

    // ---- ledger invariants ---------------------------------------------------------------------------------

    private void checkLedger(UUID runId, Stats stats) {
        for (UUID id : jdbc.query("""
                select journal_id from ledger_entries group by journal_id
                having sum(case when direction = 'CREDIT' then amount_paise else -amount_paise end) <> 0""",
                (rs, i) -> rs.getObject(1, UUID.class))) {
            ledgerMismatch(runId, null, null, "Ledger journal " + id + " does not balance to zero", stats);
        }
        for (Map<String, Object> row : jdbc.queryForList("""
                select a.id, a.balance_paise,
                       coalesce((select sum(case when e.direction = 'CREDIT' then e.amount_paise else -e.amount_paise end)
                                 from ledger_entries e where e.account_id = a.id), 0) as ledger
                from accounts a
                where a.balance_paise <> coalesce((select sum(case when e.direction = 'CREDIT' then e.amount_paise else -e.amount_paise end)
                                                   from ledger_entries e where e.account_id = a.id), 0)""")) {
            ledgerMismatch(runId, null, (UUID) row.get("id"),
                    "Account balance " + row.get("balance_paise") + " differs from ledger movement " + row.get("ledger"), stats);
        }
        for (Map<String, Object> row : jdbc.queryForList("""
                select a.id, a.reserved_paise,
                       coalesce((select sum(p.amount_paise) from payments p
                                 where p.payer_account_id = a.id and p.hold_status = 'HELD'), 0) as held
                from accounts a
                where a.reserved_paise <> coalesce((select sum(p.amount_paise) from payments p
                                                    where p.payer_account_id = a.id and p.hold_status = 'HELD'), 0)""")) {
            ledgerMismatch(runId, null, (UUID) row.get("id"),
                    "Reserved funds " + row.get("reserved_paise") + " differ from held payments " + row.get("held"), stats);
        }
        for (UUID id : jdbc.query("""
                select p.id from payments p
                where (select count(*) from ledger_journals j where j.journal_type = 'PAYMENT_TRANSFER' and j.reference_id = p.id)
                      <> case when p.status = 'SUCCESS' then 1 else 0 end""", (rs, i) -> rs.getObject(1, UUID.class))) {
            ledgerMismatch(runId, id, null, "Payment must have exactly one transfer journal if and only if it is SUCCESS", stats);
        }
    }

    private void ledgerMismatch(UUID runId, UUID paymentId, UUID accountId, String detail, Stats stats) {
        stats.ledger++;
        log.error("LEDGER INCONSISTENCY: {}", detail);
        item(runId, paymentId, accountId, CheckKind.LEDGER, null, null, null, Outcome.LEDGER_MISMATCH, detail, null);
    }

    // ---- bookkeeping ---------------------------------------------------------------------------------------

    private void item(UUID runId, UUID paymentId, UUID accountId, CheckKind kind, String before, String after,
                      String bank, Outcome outcome, String detail, Long recoveryMs) {
        jdbc.update("""
                insert into reconciliation_items (id, run_id, payment_id, account_id, check_kind, status_before, status_after,
                                                  bank_status, outcome, detail, recovery_ms)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                UUID.randomUUID(), runId, paymentId, accountId, kind.name(), before, after, bank, outcome.name(),
                truncate(detail, 300), recoveryMs);
    }

    private void finishRun(UUID runId, Instant started, Instant finished, String status, String error, Stats s) {
        // The run record and its event commit together (transactional outbox).
        tx.executeWithoutResult(t -> {
            updateRun(runId, started, finished, status, error, s);
            if (s.checked > 0 || s.conflicts > 0 || s.ledger > 0 || s.postSettlementChecked > 0 || "FAILED".equals(status)) {
                java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
                data.put("runId", runId);
                data.put("status", status);
                data.put("checked", s.checked);
                data.put("resolved", s.resolved);
                data.put("stillPending", s.stillPending);
                data.put("unknown", s.unknown);
                data.put("escalated", s.escalated);
                data.put("conflicts", s.conflicts);
                data.put("ledgerInconsistencies", s.ledger);
                outbox.record(EventTypes.RECONCILIATION_COMPLETED, "RECONCILIATION_RUN", runId, data);
            }
        });
    }

    private void updateRun(UUID runId, Instant started, Instant finished, String status, String error, Stats s) {
        jdbc.update("""
                update reconciliation_runs set status = ?, finished_at = ?, duration_ms = ?, checked = ?, resolved = ?,
                    resolved_success = ?, resolved_failed = ?, still_pending = ?, unknown = ?, conflicts = ?,
                    ledger_inconsistencies = ?, escalated = ?, post_settlement_checked = ?, max_recovery_ms = ?,
                    avg_recovery_ms = ?, error = ?
                where id = ?""",
                status, ts(finished), Duration.between(started, finished).toMillis(), s.checked, s.resolved,
                s.resolvedSuccess, s.resolvedFailed, s.stillPending, s.unknown, s.conflicts, s.ledger, s.escalated,
                s.postSettlementChecked, s.recoveryCount == 0 ? null : s.maxRecoveryMs,
                s.recoveryCount == 0 ? null : s.recoverySumMs / s.recoveryCount, error, runId);
    }

    public java.util.Optional<RunSummary> summary(UUID runId) {
        return jdbc.query("select * from reconciliation_runs where id = ?", ReconciliationWorker::mapRun, runId)
                .stream().findFirst();
    }

    static RunSummary mapRun(ResultSet rs, int i) throws SQLException {
        return new RunSummary(rs.getObject("id", UUID.class), TriggerType.valueOf(rs.getString("trigger_type")),
                rs.getString("status"), instant(rs, "started_at"), instant(rs, "finished_at"), nullableLong(rs, "duration_ms"),
                rs.getInt("checked"), rs.getInt("resolved"), rs.getInt("resolved_success"), rs.getInt("resolved_failed"),
                rs.getInt("still_pending"), rs.getInt("unknown"), rs.getInt("conflicts"), rs.getInt("ledger_inconsistencies"),
                rs.getInt("escalated"), rs.getInt("post_settlement_checked"), nullableLong(rs, "max_recovery_ms"),
                nullableLong(rs, "avg_recovery_ms"), rs.getString("error"));
    }

    static Instant instant(ResultSet rs, String col) throws SQLException {
        OffsetDateTime t = rs.getObject(col, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }

    static Long nullableLong(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    /** JDBC maps timestamptz to java.sql.Timestamp in untyped queries and to OffsetDateTime in typed ones. */
    public static Instant toInstant(Object o) {
        if (o instanceof java.sql.Timestamp t) {
            return t.toInstant();
        }
        if (o instanceof OffsetDateTime t) {
            return t.toInstant();
        }
        throw new IllegalArgumentException("not a timestamp: " + o);
    }

    static OffsetDateTime ts(Instant i) {
        return OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    /** Mutable counters of one run. */
    private static final class Stats {
        int checked, resolved, resolvedSuccess, resolvedFailed, stillPending, unknown, conflicts, ledger, escalated,
                postSettlementChecked, recoveryCount;
        long maxRecoveryMs, recoverySumMs;

        void recovery(long ms) {
            recoveryCount++;
            recoverySumMs += ms;
            maxRecoveryMs = Math.max(maxRecoveryMs, ms);
        }
    }
}
