package com.paysure.reliability;

import com.paysure.bank.BankContracts.BankSideView;
import com.paysure.bank.BankContracts.SimulationOptions;
import com.paysure.bank.SimulationScenario;
import com.paysure.bank.simulator.SimulatedBank;
import com.paysure.common.error.BadRequestException;
import com.paysure.common.error.BusinessRuleException;
import com.paysure.common.error.NotFoundException;
import com.paysure.identity.application.Caller;
import com.paysure.identity.domain.Role;
import com.paysure.payment.application.PaymentCommand;
import com.paysure.payment.application.PaymentQueryService;
import com.paysure.payment.application.PaymentQueryService.PaymentDetail;
import com.paysure.payment.application.PaymentService;
import com.paysure.payment.domain.Payment;
import com.paysure.payment.domain.PaymentStateChange;
import com.paysure.reconciliation.ReconciliationModel.Item;
import com.paysure.reconciliation.ReconciliationModel.TriggerType;
import com.paysure.reconciliation.ReconciliationReportService;
import com.paysure.reconciliation.ReconciliationWorker;
import com.paysure.reliability.ReliabilityScoreService.Score;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Payment Reliability Lab. Injects a failure scenario into the SIMULATED bank for a sandbox payment between
 * sandbox accounts, then lets an operator trigger/observe recovery. Nothing here can touch anything outside
 * the simulator: faults are options on the simulated bank's transfer, and the payment goes through the same
 * production code path (intake → bank → finalizer) as any other payment.
 */
@Service
public class LabService {

    private static final Logger log = LoggerFactory.getLogger(LabService.class);
    static final long DEFAULT_AMOUNT_PAISE = 50_000;
    static final long MAX_AMOUNT_PAISE = 500_000;

    private final JdbcTemplate jdbc;
    private final PaymentService payments;
    private final PaymentQueryService queries;
    private final SimulatedBank bank;
    private final ReconciliationWorker reconciliation;
    private final ReconciliationReportService reconReports;
    private final ReliabilityScoreService scores;
    private final Clock clock;

    public LabService(JdbcTemplate jdbc, PaymentService payments, PaymentQueryService queries, SimulatedBank bank,
                      ReconciliationWorker reconciliation, ReconciliationReportService reconReports,
                      ReliabilityScoreService scores, Clock clock) {
        this.jdbc = jdbc;
        this.payments = payments;
        this.queries = queries;
        this.bank = bank;
        this.reconciliation = reconciliation;
        this.reconReports = reconReports;
        this.scores = scores;
        this.clock = clock;
    }

    // ---- model ---------------------------------------------------------------------------------------------

    public record Scenario(String code, String title, String category, String bankScenario, int defaultDelaySeconds,
                           int defaultDuplicates, int defaultRequests, boolean statusApiDown, String whatHappens,
                           String expectedOutcome, String recovery) {
    }

    public record StartCommand(UUID callerId, String scenarioCode, Long amountPaise, Integer delaySeconds,
                               Integer duplicateCallbacks, String payerVpa, String payeeVpa) {
    }

    public record Balances(long payerBalancePaise, long payerReservedPaise, long payeeBalancePaise) {
    }

    public record PaymentState(UUID id, String status, String holdStatus, String failureCode, long amountPaise,
                               String bankReference, boolean needsManualReview, String manualReviewReason) {
    }

    public record LedgerLeg(String direction, String account, long amountPaise) {
    }

    public record TimelineEvent(Instant at, String actor, String title, String detail) {
    }

    public record RunListItem(UUID id, String scenarioCode, String scenarioTitle, Instant createdAt, UUID paymentId,
                              String paymentStatus, long amountPaise) {
    }

    public record LabView(UUID id, Scenario scenario, Instant createdAt, long amountPaise, int delaySeconds,
                          int duplicateCallbacks, int requestsSent, int distinctPayments, String payerVpa, String payeeVpa,
                          Balances before, Balances afterSubmit, Balances now, String statusAfterSubmit,
                          PaymentState payment, BankSideView simulatedBank, boolean statusApiUp, List<LedgerLeg> ledger,
                          List<TimelineEvent> timeline, List<Item> reconciliation, Integer scoreBefore, Integer scoreNow,
                          List<String> explanation, List<String> availableActions, boolean finished) {
    }

    public enum Action { RECONCILE, FAST_FORWARD, RESTORE_STATUS_API }

    // ---- scenarios -----------------------------------------------------------------------------------------

    public List<Scenario> scenarios() {
        return jdbc.query("select * from fault_scenarios order by sort_order", (rs, i) -> scenario(rs));
    }

    private Optional<Scenario> scenario(String code) {
        return jdbc.query("select * from fault_scenarios where code = ?", (rs, i) -> scenario(rs), code).stream().findFirst();
    }

    private static Scenario scenario(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Scenario(rs.getString("code"), rs.getString("title"), rs.getString("category"),
                rs.getString("bank_scenario"), rs.getInt("default_delay_s"), rs.getInt("default_duplicates"),
                rs.getInt("default_requests"), rs.getBoolean("status_api_down"), rs.getString("what_happens"),
                rs.getString("expected_outcome"), rs.getString("recovery"));
    }

    // ---- start ---------------------------------------------------------------------------------------------

    public LabView start(StartCommand cmd) {
        Instant startedAt = clock.instant(); // the client request is sent now, before any payment event
        Scenario sc = scenario(cmd.scenarioCode())
                .orElseThrow(() -> new NotFoundException("SCENARIO_NOT_FOUND", "Unknown scenario."));
        long amount = cmd.amountPaise() == null ? DEFAULT_AMOUNT_PAISE : cmd.amountPaise();
        if (amount <= 0 || amount > MAX_AMOUNT_PAISE) {
            throw new BadRequestException("INVALID_AMOUNT", "Lab amount must be between 1 and " + MAX_AMOUNT_PAISE + " paise.");
        }
        int delay = cmd.delaySeconds() == null ? sc.defaultDelaySeconds() : cmd.delaySeconds();
        int dups = cmd.duplicateCallbacks() == null ? sc.defaultDuplicates() : cmd.duplicateCallbacks();
        SimulationOptions sim = new SimulationOptions(SimulationScenario.valueOf(sc.bankScenario()), delay, dups);

        String payerVpa = cmd.payerVpa() != null && !cmd.payerVpa().isBlank() ? cmd.payerVpa().trim().toLowerCase()
                : ownVpa(cmd.callerId()).orElseThrow(() -> new BusinessRuleException("NO_VPA",
                "Create a sandbox account with a VPA first."));
        String payeeVpa = cmd.payeeVpa() != null && !cmd.payeeVpa().isBlank() ? cmd.payeeVpa().trim().toLowerCase()
                : counterpartyVpa(cmd.callerId()).orElseThrow(() -> new BusinessRuleException("NO_COUNTERPARTY",
                "No other active sandbox VPA exists to pay. Register a second user first."));
        UUID payerAccount = accountOf(payerVpa);
        UUID payeeAccount = accountOf(payeeVpa);

        Balances before = balances(payerAccount, payeeAccount);
        Integer scoreBefore = scores.compute().score();
        scores.captureSnapshot("LAB_BEFORE");

        bank.setStatusApiAvailable(!sc.statusApiDown());
        int requests = Math.max(1, sc.defaultRequests());
        String key = "lab-" + UUID.randomUUID();
        PaymentCommand payCmd = new PaymentCommand(cmd.callerId(), true, key, payerVpa, payeeVpa, amount,
                "Lab: " + sc.title(), sim, null);
        List<Payment> results = submit(payCmd, requests);
        Payment first = results.get(0);
        int distinct = (int) results.stream().map(Payment::getId).distinct().count();

        Balances afterSubmit = balances(payerAccount, payeeAccount);
        String statusAfterSubmit = jdbc.queryForObject("select status from payments where id = ?", String.class, first.getId());
        scores.captureSnapshot("LAB_FAULT");

        UUID runId = UUID.randomUUID();
        jdbc.update("""
                insert into lab_runs (id, scenario_code, started_by, payment_id, payer_account_id, payee_account_id, payer_vpa,
                    payee_vpa, amount_paise, delay_seconds, duplicate_callbacks, duplicate_requests, status_api_down,
                    payer_balance_before, payer_reserved_before, payee_balance_before, payer_balance_after_submit,
                    payer_reserved_after_submit, payee_balance_after_submit, status_after_submit, distinct_payments_created,
                    score_before, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                runId, sc.code(), cmd.callerId(), first.getId(), payerAccount, payeeAccount, payerVpa, payeeVpa, amount,
                delay, dups, requests, sc.statusApiDown(), before.payerBalancePaise(), before.payerReservedPaise(),
                before.payeeBalancePaise(), afterSubmit.payerBalancePaise(), afterSubmit.payerReservedPaise(),
                afterSubmit.payeeBalancePaise(), statusAfterSubmit, distinct, scoreBefore, odt(startedAt));
        log.info("Lab run {} started: {} ({} request(s), {} distinct payment(s), status after submit {})",
                runId, sc.code(), requests, distinct, statusAfterSubmit);
        return view(runId, cmd.callerId());
    }

    private List<Payment> submit(PaymentCommand cmd, int requests) {
        if (requests == 1) {
            return List.of(payments.initiate(cmd).payment());
        }
        ExecutorService pool = Executors.newFixedThreadPool(requests);
        try {
            List<Future<Payment>> futures = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                futures.add(pool.submit(() -> payments.initiate(cmd).payment()));
            }
            List<Payment> out = new ArrayList<>();
            for (Future<Payment> f : futures) {
                out.add(f.get());
            }
            return out;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while submitting lab requests", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException(e.getCause());
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- actions -------------------------------------------------------------------------------------------

    public LabView act(UUID runId, UUID callerId, Action action, int repeat) {
        row(runId); // 404 if unknown
        switch (action) {
            case RECONCILE -> {
                for (int i = 0; i < Math.max(1, Math.min(repeat, 10)); i++) {
                    reconciliation.run(TriggerType.LAB, Duration.ZERO);
                }
                scores.captureSnapshot("LAB_RECOVERY");
            }
            case FAST_FORWARD -> {
                int delay = jdbc.queryForObject("select delay_seconds from lab_runs where id = ?", Integer.class, runId);
                bank.tick(clock.instant().plusSeconds(delay + 5L));
                scores.captureSnapshot("LAB_RECOVERY");
            }
            case RESTORE_STATUS_API -> bank.setStatusApiAvailable(true);
        }
        return view(runId, callerId);
    }

    public List<RunListItem> runs(int limit) {
        return jdbc.query("""
                select r.id, r.scenario_code, s.title, r.created_at, r.payment_id, p.status, r.amount_paise
                from lab_runs r join fault_scenarios s on s.code = r.scenario_code
                left join payments p on p.id = r.payment_id
                order by r.created_at desc limit ?""", (rs, i) -> new RunListItem(rs.getObject("id", UUID.class),
                rs.getString("scenario_code"), rs.getString("title"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(), rs.getObject("payment_id", UUID.class),
                rs.getString("status"), rs.getLong("amount_paise")), Math.max(1, Math.min(limit, 50)));
    }

    // ---- view ----------------------------------------------------------------------------------------------

    public LabView view(UUID runId, UUID callerId) {
        var r = row(runId);
        Scenario sc = scenario((String) r.get("scenario_code")).orElseThrow();
        UUID paymentId = (UUID) r.get("payment_id");
        UUID payerAccount = (UUID) r.get("payer_account_id");
        UUID payeeAccount = (UUID) r.get("payee_account_id");
        Instant createdAt = ReconciliationWorker.toInstant(r.get("created_at"));
        long amount = ((Number) r.get("amount_paise")).longValue();
        int requests = ((Number) r.get("duplicate_requests")).intValue();

        PaymentDetail d = queries.detail(new Caller(callerId, Role.ADMIN_DEMO), paymentId);
        Payment p = d.payment();
        var flags = jdbc.queryForMap("select needs_manual_review, manual_review_reason from payments where id = ?", paymentId);
        PaymentState state = new PaymentState(p.getId(), p.getStatus().name(), p.getHoldStatus().name(), p.getFailureCode(),
                p.getAmountPaise(), d.attempts().isEmpty() ? null : d.attempts().get(d.attempts().size() - 1).getBankReference(),
                (Boolean) flags.get("needs_manual_review"), (String) flags.get("manual_review_reason"));
        List<Item> recon = reconReports.itemsForPayment(paymentId);
        List<LedgerLeg> ledger = jdbc.query("""
                select e.direction, e.account_id, e.amount_paise from ledger_entries e
                join ledger_journals j on j.id = e.journal_id
                where j.journal_type = 'PAYMENT_TRANSFER' and j.reference_id = ? order by e.direction""",
                (rs, i) -> new LedgerLeg(rs.getString("direction"),
                        payerAccount.equals(rs.getObject("account_id", UUID.class)) ? "PAYER" : "PAYEE", rs.getLong("amount_paise")),
                paymentId);
        Balances nowBalances = balances(payerAccount, payeeAccount);
        Balances before = new Balances(((Number) r.get("payer_balance_before")).longValue(),
                ((Number) r.get("payer_reserved_before")).longValue(), ((Number) r.get("payee_balance_before")).longValue());
        Balances afterSubmit = new Balances(((Number) r.get("payer_balance_after_submit")).longValue(),
                ((Number) r.get("payer_reserved_after_submit")).longValue(), ((Number) r.get("payee_balance_after_submit")).longValue());
        Integer scoreBefore = r.get("score_before") == null ? null : ((Number) r.get("score_before")).intValue();
        Score scoreNow = scores.compute();
        int distinct = ((Number) r.get("distinct_payments_created")).intValue();

        List<TimelineEvent> timeline = timeline(createdAt, sc, requests, d, recon);
        boolean finished = p.getStatus().isTerminal();
        return new LabView(runId, sc, createdAt, amount, ((Number) r.get("delay_seconds")).intValue(),
                ((Number) r.get("duplicate_callbacks")).intValue(), requests, distinct, (String) r.get("payer_vpa"),
                (String) r.get("payee_vpa"), before, afterSubmit, nowBalances, (String) r.get("status_after_submit"), state,
                d.bankSide().orElse(null), bank.isStatusApiAvailable(), ledger, timeline, recon, scoreBefore,
                scoreNow.score(), explanation(sc, state, d, ledger, recon, requests, distinct, amount), actions(state, d, sc), finished);
    }

    private List<String> actions(PaymentState state, PaymentDetail d, Scenario sc) {
        List<String> out = new ArrayList<>();
        if (!"SUCCESS".equals(state.status()) && !"FAILED".equals(state.status())) {
            out.add(Action.RECONCILE.name());
            if (d.bankSide().map(b -> "PROCESSING".equals(b.committedStatus().name())).orElse(false)) {
                out.add(Action.FAST_FORWARD.name());
            }
        }
        if (!bank.isStatusApiAvailable()) {
            out.add(Action.RESTORE_STATUS_API.name());
        }
        return out;
    }

    private List<TimelineEvent> timeline(Instant createdAt, Scenario sc, int requests, PaymentDetail d, List<Item> recon) {
        List<TimelineEvent> ev = new ArrayList<>();
        ev.add(new TimelineEvent(createdAt, "CLIENT", requests == 1 ? "Payment request sent" : requests + " identical requests sent at once",
                requests == 1 ? "Idempotency-Key attached" : "All carry the same Idempotency-Key"));
        for (PaymentStateChange h : d.history()) {
            String actor = switch (h.getSource()) {
                case API -> "PAYSURE";
                case BANK_RESPONSE, BANK_CALLBACK -> "BANK";
                case STATUS_CHECK, RECONCILIATION -> "RECONCILER";
            };
            ev.add(new TimelineEvent(h.getCreatedAt(), actor, "Status → " + h.getToStatus(), h.getReason()));
        }
        d.bankSide().ifPresent(b -> {
            ev.add(new TimelineEvent(b.createdAt(), "BANK", "Bank received the transfer", "Simulated behaviour: " + b.scenario()));
            if (b.committedAt() != null) {
                ev.add(new TimelineEvent(b.committedAt(), "BANK", "Bank committed " + b.committedStatus(),
                        "What the bank actually did (it told PaySure: " + b.responseDelivered() + ")"));
            }
            b.callbacks().forEach(c -> {
                if (c.deliveredAt() != null) {
                    ev.add(new TimelineEvent(c.deliveredAt(), "BANK", "Callback #" + c.sequence() + " delivered (" + c.reportedStatus() + ")",
                            "Delivery attempt " + c.deliveryAttempts()));
                }
            });
        });
        d.attempts().forEach(a -> {
            if (a.getResponseAt() != null) {
                ev.add(new TimelineEvent(a.getResponseAt(), "BANK", "Bank replied: " + a.getResponseKind(),
                        a.getResponseKind() != null && a.getResponseKind().isAmbiguous()
                                ? "Ambiguous reply — PaySure cannot tell if money moved" : "Definitive reply"));
            }
        });
        recon.forEach(i -> ev.add(new TimelineEvent(i.createdAt(), "RECONCILER", "Reconciliation: " + i.outcome(), i.detail())));
        ev.sort(Comparator.comparing(TimelineEvent::at));
        return ev;
    }

    private List<String> explanation(Scenario sc, PaymentState state, PaymentDetail d, List<LedgerLeg> ledger,
                                     List<Item> recon, int requests, int distinct, long amount) {
        List<String> out = new ArrayList<>();
        out.add("What was injected: " + sc.whatHappens());
        if (requests > 1) {
            out.add(requests + " identical requests produced " + distinct + " logical payment(s). The Idempotency-Key is claimed "
                    + "atomically with the payment, so the other " + (requests - 1) + " requests received the same payment back.");
        }
        Set<String> sources = new HashSet<>();
        d.history().forEach(h -> sources.add(h.getSource().name()));
        String rupees = String.format(java.util.Locale.ROOT, "₹%.2f", amount / 100.0);
        switch (state.status()) {
            case "PENDING", "PROCESSING" -> {
                out.add("PaySure does not know the outcome, so it holds " + rupees + " (not spendable, not yet debited) and keeps the "
                        + "payment PENDING. It will never re-send the transfer to find out.");
                if (!bank.isStatusApiAvailable()) {
                    out.add("The bank's status API is down, so even a verification is impossible. Reconciliation records the outcome as "
                            + "UNKNOWN and retries; restore the status API and run reconciliation again.");
                }
                if (state.needsManualReview()) {
                    out.add("Escalated to manual review: " + state.manualReviewReason() + ". It is still PENDING and still being retried.");
                }
            }
            case "SUCCESS" -> {
                if (sources.contains("RECONCILIATION") || sources.contains("STATUS_CHECK")) {
                    out.add("Recovery: reconciliation asked the bank about the stable reference (a question, never a new transfer), "
                            + "the bank confirmed SUCCESS, and PaySure finalised the payment.");
                } else if (sources.contains("BANK_CALLBACK")) {
                    out.add("Recovery: the bank's callback confirmed SUCCESS and PaySure finalised the payment once.");
                }
                out.add("Ledger: " + ledger.size() + " entries in " + (ledger.isEmpty() ? 0 : 1) + " journal — one debit and one credit of "
                        + rupees + ". Applying the result is exactly-once even though several signals may have arrived.");
            }
            case "FAILED" -> out.add("Final result: FAILED (" + state.failureCode() + "). The " + rupees
                    + " hold was released and nothing was debited: " + state.holdStatus().toLowerCase() + ".");
            default -> {
            }
        }
        int ignored = d.attempts().stream().mapToInt(a -> a.getCallbacksIgnored()).sum();
        if (ignored > 0) {
            out.add(ignored + " duplicate or stale callback(s) were ignored; they changed nothing.");
        }
        out.add("Expected for this scenario: " + sc.expectedOutcome());
        return out;
    }

    // ---- data helpers --------------------------------------------------------------------------------------

    private java.util.Map<String, Object> row(UUID runId) {
        List<java.util.Map<String, Object>> rows = jdbc.queryForList("select * from lab_runs where id = ?", runId);
        if (rows.isEmpty()) {
            throw new NotFoundException("LAB_RUN_NOT_FOUND", "Lab run not found.");
        }
        return rows.get(0);
    }

    private Balances balances(UUID payer, UUID payee) {
        var p = jdbc.queryForMap("select balance_paise, reserved_paise from accounts where id = ?", payer);
        Long payeeBal = jdbc.queryForObject("select balance_paise from accounts where id = ?", Long.class, payee);
        return new Balances(((Number) p.get("balance_paise")).longValue(), ((Number) p.get("reserved_paise")).longValue(), payeeBal);
    }

    private Optional<String> ownVpa(UUID userId) {
        return jdbc.query("""
                select h.vpa from upi_handles h join accounts a on a.id = h.account_id
                where a.user_id = ? and a.status = 'ACTIVE' and h.status = 'ACTIVE' order by h.created_at limit 1""",
                (rs, i) -> rs.getString(1), userId).stream().findFirst();
    }

    private Optional<String> counterpartyVpa(UUID userId) {
        return jdbc.query("""
                select h.vpa from upi_handles h join accounts a on a.id = h.account_id
                where a.account_type = 'CUSTOMER' and a.user_id <> ? and a.status = 'ACTIVE' and h.status = 'ACTIVE'
                order by h.created_at limit 1""", (rs, i) -> rs.getString(1), userId).stream().findFirst();
    }

    private UUID accountOf(String vpa) {
        return jdbc.query("select account_id from upi_handles where vpa = ?", (rs, i) -> rs.getObject(1, UUID.class), vpa)
                .stream().findFirst().orElseThrow(() -> new NotFoundException("VPA_NOT_FOUND", "VPA not found: " + vpa));
    }

    private static OffsetDateTime odt(Instant i) {
        return OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
    }
}
