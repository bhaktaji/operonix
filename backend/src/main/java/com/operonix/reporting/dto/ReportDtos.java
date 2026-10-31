package com.operonix.reporting.dto;

import com.operonix.scheduling.dto.JobDtos;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public final class ReportDtos {

    private ReportDtos() {
    }

    public record Bucket(String key, String label, BigDecimal revenue, long invoiceCount) {
    }

    public record ServiceRevenue(String label, BigDecimal revenue) {
    }

    public record Dashboard(
            BigDecimal revenueThisMonth,
            BigDecimal revenuePreviousMonth,
            BigDecimal revenueDeltaPct,
            BigDecimal outstandingTotal,
            BigDecimal overdueTotal,
            long jobsToday,
            long jobsThisWeek,
            Long quotesPending,
            BigDecimal quoteAcceptanceRate,
            Long activeCustomers,
            List<Bucket> revenueByDay,
            List<ServiceRevenue> topServices,
            List<JobDtos.Response> upcomingJobs
    ) {
    }

    public record RevenueReport(String groupBy, List<Bucket> buckets, BigDecimal total) {
    }

    public record TechnicianPerformance(UUID userId, String fullName, long jobsCompleted,
                                        long scheduledMinutes) {
    }
}
