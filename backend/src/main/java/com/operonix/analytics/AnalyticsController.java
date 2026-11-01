package com.operonix.analytics;

import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Business intelligence API. Manager and above: these endpoints expose whole-workspace financial
 * performance, which a field technician has no reason to read.
 */
@RestController
@RequestMapping("/api/v1/analytics")
@PreAuthorize("hasAnyRole('OWNER','ADMIN','MANAGER')")
public class AnalyticsController {

    private final AnalyticsService analytics;

    public AnalyticsController(AnalyticsService analytics) {
        this.analytics = analytics;
    }

    @GetMapping("/catalogue")
    public List<AnalyticsDtos.MetricInfo> catalogue() {
        return analytics.catalogue();
    }

    @GetMapping("/overview")
    public AnalyticsDtos.Overview overview(@RequestParam(required = false) LocalDate from,
                                           @RequestParam(required = false) LocalDate to,
                                           @RequestParam(required = false) UUID locationId) {
        return analytics.overview(from, to, locationId);
    }

    @GetMapping("/trend")
    public AnalyticsDtos.Trend trend(@RequestParam String metric,
                                     @RequestParam(required = false) LocalDate from,
                                     @RequestParam(required = false) LocalDate to,
                                     @RequestParam(defaultValue = "day") String granularity,
                                     @RequestParam(required = false) UUID locationId) {
        return analytics.trend(metric, from, to, granularity, locationId);
    }

    @GetMapping("/breakdown")
    public AnalyticsDtos.Breakdown breakdown(@RequestParam(defaultValue = "LOCATION") String dimension,
                                             @RequestParam(required = false) LocalDate from,
                                             @RequestParam(required = false) LocalDate to) {
        return analytics.breakdown(dimension, from, to);
    }

    @GetMapping("/locations")
    public List<AnalyticsDtos.BreakdownRow> locations() {
        return analytics.locations();
    }

    @GetMapping("/ageing")
    public AnalyticsDtos.Ageing ageing() {
        return analytics.ageing();
    }

    @GetMapping("/jobs")
    public AnalyticsDtos.JobProfitability jobs(@RequestParam(required = false) LocalDate from,
                                               @RequestParam(required = false) LocalDate to,
                                               @RequestParam(required = false) UUID locationId) {
        return analytics.jobProfitability(from, to, locationId);
    }

    @GetMapping("/status")
    public AnalyticsDtos.RefreshStatus status() {
        return analytics.status();
    }

    @PostMapping("/refresh")
    public AnalyticsDtos.RefreshStatus refresh(@RequestBody(required = false)
                                               @Valid AnalyticsDtos.RefreshRequest request) {
        return analytics.refresh(request == null ? null : request.from(),
                request == null ? null : request.to());
    }
}
