package com.paysure.reliability.api;

import com.paysure.reliability.ReliabilityScoreService;
import com.paysure.reliability.ReliabilityScoreService.Score;
import com.paysure.reliability.ReliabilityScoreService.Snapshot;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Clock;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/reliability")
@PreAuthorize("hasRole('ADMIN_DEMO')")
@Tag(name = "Reliability score")
public class ReliabilityController {

    private final ReliabilityScoreService scores;
    private final Clock clock;

    public ReliabilityController(ReliabilityScoreService scores, Clock clock) {
        this.scores = scores;
        this.clock = clock;
    }

    @GetMapping
    @Operation(summary = "Current explainable reliability score and its components",
            description = "A configurable demo score computed from stored events; not an industry benchmark.")
    public Score score(@RequestParam(defaultValue = "60") int windowMinutes) {
        int minutes = Math.max(1, Math.min(windowMinutes, 24 * 60 * 7));
        return scores.compute(clock.instant().minusSeconds(minutes * 60L));
    }

    @GetMapping("/history")
    public List<Snapshot> history(@RequestParam(defaultValue = "100") int limit) {
        return scores.history(limit);
    }

    @PostMapping("/snapshots")
    @Operation(summary = "Store a snapshot of the current score (returns null if there is no data yet)")
    public Snapshot snapshot() {
        return scores.captureSnapshot("MANUAL");
    }
}
