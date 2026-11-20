package com.paysure.reconciliation.api;

import com.paysure.reconciliation.ReconciliationModel.Item;
import com.paysure.reconciliation.ReconciliationModel.ManualReviewCase;
import com.paysure.reconciliation.ReconciliationModel.RunSummary;
import com.paysure.reconciliation.ReconciliationModel.TriggerType;
import com.paysure.reconciliation.ReconciliationReportService;
import com.paysure.reconciliation.ReconciliationReportService.Report;
import com.paysure.reconciliation.ReconciliationWorker;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Operator view of reconciliation (system-wide data, so ADMIN_DEMO only — also enforced by the /api/admin/** URL rule). */
@RestController
@RequestMapping("/api/admin/reconciliation")
@PreAuthorize("hasRole('ADMIN_DEMO')")
@Tag(name = "Reconciliation")
public class ReconciliationController {

    private final ReconciliationWorker worker;
    private final ReconciliationReportService reports;

    public ReconciliationController(ReconciliationWorker worker, ReconciliationReportService reports) {
        this.worker = worker;
        this.reports = reports;
    }

    @PostMapping("/run")
    @Operation(summary = "Run reconciliation now",
            description = "minAgeSeconds=0 checks every unfinished payment immediately instead of waiting for the usual minimum age.")
    public RunSummary run(@RequestParam(required = false) Integer minAgeSeconds) {
        return minAgeSeconds == null ? worker.run(TriggerType.MANUAL)
                : worker.run(TriggerType.MANUAL, Duration.ofSeconds(Math.max(0, Math.min(minAgeSeconds, 86_400))));
    }

    @GetMapping("/report")
    @Operation(summary = "Aggregate reconciliation report over the last N hours")
    public Report report(@RequestParam(defaultValue = "24") int hours) {
        return reports.report(hours);
    }

    @GetMapping("/runs")
    public List<RunSummary> runs(@RequestParam(defaultValue = "20") int limit) {
        return reports.runs(limit);
    }

    @GetMapping("/runs/{runId}/items")
    public List<Item> items(@PathVariable UUID runId) {
        return reports.items(runId);
    }

    @GetMapping("/manual-review")
    @Operation(summary = "Payments flagged for manual review (unresolved after repeated checks, or conflicting records)")
    public List<ManualReviewCase> manualReview() {
        return reports.manualReview();
    }
}
