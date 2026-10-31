package com.operonix.reporting;

import com.operonix.reporting.dto.ReportDtos;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/reports")
@PreAuthorize("hasAnyRole('OWNER','ADMIN','MANAGER','TECHNICIAN')")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping("/dashboard")
    public ReportDtos.Dashboard dashboard() {
        return reportService.dashboard();
    }

    @GetMapping("/revenue")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','MANAGER')")
    public ReportDtos.RevenueReport revenue(@RequestParam LocalDate from,
                                            @RequestParam LocalDate to,
                                            @RequestParam(defaultValue = "day") String groupBy) {
        return reportService.revenue(from, to, groupBy);
    }

    @GetMapping("/technicians")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','MANAGER')")
    public List<ReportDtos.TechnicianPerformance> technicians(@RequestParam LocalDate from,
                                                              @RequestParam LocalDate to) {
        return reportService.technicianPerformance(from, to);
    }
}
