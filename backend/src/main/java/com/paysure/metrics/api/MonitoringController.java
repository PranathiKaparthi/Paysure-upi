package com.paysure.metrics.api;

import com.paysure.metrics.MonitoringService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/monitoring")
@PreAuthorize("hasRole('ADMIN_DEMO')")
@Tag(name = "Monitoring")
public class MonitoringController {

    private final MonitoringService monitoring;

    public MonitoringController(MonitoringService monitoring) {
        this.monitoring = monitoring;
    }

    @GetMapping("/summary")
    @Operation(summary = "Payment, pipeline, risk and dependency figures; counters are since process start")
    public Map<String, Object> summary() {
        return monitoring.summary();
    }

    @GetMapping("/series")
    @Operation(summary = "30 one-minute buckets of payments, webhook attempts and reconciliation results")
    public Map<String, Object> series() {
        return monitoring.series();
    }
}
