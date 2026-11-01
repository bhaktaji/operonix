package com.operonix.analytics;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class AnalyticsDtos {

    private AnalyticsDtos() {
    }

    public record MetricInfo(String key, String label, String unit, String formula,
                             List<String> inputs, boolean rolledUp) {

        static MetricInfo from(MetricDefinition.Metric metric) {
            return new MetricInfo(metric.key(), metric.label(), metric.unit().name(), metric.formula(),
                    metric.inputs(), metric.rolledUp());
        }
    }

    /**
     * A single metric reading.
     *
     * @param available false when the workspace has no data to compute it from; {@code value} is then
     *                  null rather than zero, and {@code reason} says why
     */
    public record MetricValue(String key, String label, String unit, BigDecimal value,
                              BigDecimal previousValue, BigDecimal changePercent,
                              boolean available, String reason) {
    }

    public record Overview(LocalDate from, LocalDate to, LocalDate previousFrom, LocalDate previousTo,
                           String currency, boolean currencyConverted, List<MetricValue> metrics,
                           List<String> notes, Instant refreshedAt) {
    }

    public record TrendPoint(LocalDate bucket, BigDecimal value) {
    }

    public record Trend(String metric, String label, String unit, String currency, String granularity,
                        List<TrendPoint> points, boolean available, String reason) {
    }

    /**
     * One drill-down row. {@code dimensions} carries the filter that reproduces it: for example
     * {@code {"serviceItemId": "…"}} or {@code {"customerId": "…"}}.
     */
    public record BreakdownRow(String label, UUID id, java.util.Map<String, String> dimensions,
                               BigDecimal revenue, BigDecimal cost, BigDecimal profit,
                               BigDecimal marginPercent, BigDecimal outstanding, Long minutes,
                               Long count, boolean profitAvailable) {
    }

    public record Breakdown(String dimension, String currency, List<BreakdownRow> rows,
                            List<String> notes) {
    }

    public record AgeingBucket(String bucket, BigDecimal amount, long invoices) {
    }

    public record InvoiceRow(UUID id, String number, String customerName, LocalDate issueDate,
                             LocalDate dueDate, BigDecimal balance, long daysOverdue) {
    }

    public record Ageing(String currency, LocalDate asOf, List<AgeingBucket> buckets,
                         BigDecimal outstanding, BigDecimal overdue, List<InvoiceRow> invoices,
                         List<String> notes) {
    }

    public record JobRow(UUID id, String number, String title, String customerName, LocalDate deliveredOn,
                         BigDecimal revenue, BigDecimal cost, BigDecimal profit, BigDecimal marginPercent,
                         boolean complete, List<String> reasons) {
    }

    public record JobProfitability(String currency, List<JobRow> rows, int incompleteCount,
                                   BigDecimal totalRevenue, BigDecimal totalCost, BigDecimal totalProfit,
                                   List<String> notes) {
    }

    public record RefreshStatus(Instant refreshedAt, LocalDate rangeStart, LocalDate rangeEnd,
                                long rowCount) {
    }

    /** Optional body for a manual refresh; defaults to the trailing 400 days. */
    public record RefreshRequest(LocalDate from, LocalDate to) {
    }
}
