package com.paysure.reconciliation;

import static com.paysure.reconciliation.ReconciliationWorker.instant;
import static com.paysure.reconciliation.ReconciliationWorker.nullableLong;
import static com.paysure.reconciliation.ReconciliationWorker.ts;

import com.paysure.reconciliation.ReconciliationModel.CheckKind;
import com.paysure.reconciliation.ReconciliationModel.Item;
import com.paysure.reconciliation.ReconciliationModel.ManualReviewCase;
import com.paysure.reconciliation.ReconciliationModel.Outcome;
import com.paysure.reconciliation.ReconciliationModel.RunSummary;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Read side of reconciliation: run history, per-run items, the aggregate report and the manual-review queue. */
@Service
public class ReconciliationReportService {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public ReconciliationReportService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Aggregate report over a time window. "Checked/resolved/escalated" are sums over runs; "still pending" and
     * "manual review" are the current state; "ledger inconsistencies" is the latest completed run's count
     * (a persistent problem would otherwise be counted once per run).
     */
    public record Report(Instant since, int hours, long runs, long checked, long resolved, long resolvedSuccess,
                         long resolvedFailed, long unresolvedUnknown, long escalated, long conflictingPayments,
                         long ledgerInconsistencies, long postSettlementChecked, Long avgRecoveryMs, Long maxRecoveryMs,
                         long stillPendingNow, long manualReviewCases) {
    }

    public Report report(int hours) {
        Instant since = clock.instant().minus(Duration.ofHours(Math.max(1, Math.min(hours, 24 * 30))));
        return jdbc.queryForObject("""
                        select
                          (select count(*) from reconciliation_runs where started_at >= ?) as runs,
                          (select coalesce(sum(checked), 0) from reconciliation_runs where started_at >= ?) as checked,
                          (select coalesce(sum(resolved), 0) from reconciliation_runs where started_at >= ?) as resolved,
                          (select coalesce(sum(resolved_success), 0) from reconciliation_runs where started_at >= ?) as rs,
                          (select coalesce(sum(resolved_failed), 0) from reconciliation_runs where started_at >= ?) as rf,
                          (select coalesce(sum(unknown), 0) from reconciliation_runs where started_at >= ?) as unk,
                          (select coalesce(sum(escalated), 0) from reconciliation_runs where started_at >= ?) as esc,
                          (select count(distinct i.payment_id) from reconciliation_items i join reconciliation_runs r on r.id = i.run_id
                             where r.started_at >= ? and i.outcome = 'CONFLICT') as conf,
                          (select coalesce(ledger_inconsistencies, 0) from reconciliation_runs
                             where status = 'COMPLETED' order by started_at desc limit 1) as led,
                          (select coalesce(sum(post_settlement_checked), 0) from reconciliation_runs where started_at >= ?) as post,
                          (select avg(i.recovery_ms)::bigint from reconciliation_items i join reconciliation_runs r on r.id = i.run_id
                             where r.started_at >= ? and i.outcome = 'RESOLVED') as avg_rec,
                          (select max(i.recovery_ms) from reconciliation_items i join reconciliation_runs r on r.id = i.run_id
                             where r.started_at >= ? and i.outcome = 'RESOLVED') as max_rec,
                          (select count(*) from payments where status in ('PROCESSING', 'PENDING')) as pending_now,
                          (select count(*) from payments where needs_manual_review) as manual""",
                (rs, i) -> new Report(since, hours, rs.getLong("runs"), rs.getLong("checked"), rs.getLong("resolved"),
                        rs.getLong("rs"), rs.getLong("rf"), rs.getLong("unk"), rs.getLong("esc"), rs.getLong("conf"),
                        rs.getLong("led"), rs.getLong("post"), nullableLong(rs, "avg_rec"), nullableLong(rs, "max_rec"),
                        rs.getLong("pending_now"), rs.getLong("manual")),
                ts(since), ts(since), ts(since), ts(since), ts(since), ts(since), ts(since), ts(since), ts(since),
                ts(since), ts(since));
    }

    public List<RunSummary> runs(int limit) {
        return jdbc.query("select * from reconciliation_runs order by started_at desc limit ?",
                ReconciliationWorker::mapRun, Math.max(1, Math.min(limit, 200)));
    }

    public List<Item> items(UUID runId) {
        return jdbc.query("select * from reconciliation_items where run_id = ? order by created_at, id",
                (rs, i) -> mapItem(rs), runId);
    }

    public List<Item> itemsForPayment(UUID paymentId) {
        return jdbc.query("select * from reconciliation_items where payment_id = ? order by created_at, id",
                (rs, i) -> mapItem(rs), paymentId);
    }

    public List<ManualReviewCase> manualReview() {
        return jdbc.query("""
                select id, status, payer_vpa, payee_vpa, amount_paise, manual_review_reason, recon_attempts, created_at,
                       last_reconciled_at
                from payments where needs_manual_review order by updated_at desc limit 200""",
                (rs, i) -> new ManualReviewCase(rs.getObject("id", UUID.class), rs.getString("status"),
                        rs.getString("payer_vpa"), rs.getString("payee_vpa"), rs.getLong("amount_paise"),
                        rs.getString("manual_review_reason"), rs.getInt("recon_attempts"), instant(rs, "created_at"),
                        instant(rs, "last_reconciled_at")));
    }

    private static Item mapItem(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Item(rs.getObject("id", UUID.class), rs.getObject("run_id", UUID.class),
                rs.getObject("payment_id", UUID.class), rs.getObject("account_id", UUID.class),
                CheckKind.valueOf(rs.getString("check_kind")), rs.getString("status_before"), rs.getString("status_after"),
                rs.getString("bank_status"), Outcome.valueOf(rs.getString("outcome")), rs.getString("detail"),
                nullableLong(rs, "recovery_ms"), instant(rs, "created_at"));
    }
}
