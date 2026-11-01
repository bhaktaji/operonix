package com.operonix.analytics;

import java.util.List;

/**
 * The analytics metric catalogue (P2a).
 *
 * <p>A metric exists here or it does not exist at all. The catalogue is the contract shared by the
 * dashboard, the drill-down endpoints and the AI layer: the AI may only name metrics from this list,
 * and each definition states the formula and the source records so a number can always be traced
 * back to what produced it.
 */
public final class MetricDefinition {

    private MetricDefinition() {
    }

    public enum Unit {
        CURRENCY,
        COUNT,
        MINUTES,
        PERCENT
    }

    /**
     * @param key        stable identifier used by the API and the AI contract
     * @param label      human label in the UI
     * @param unit       how the value is formatted
     * @param formula    the exact calculation, in words
     * @param inputs     source records the value is derived from
     * @param rolledUp   true when the value is materialised in metric_daily
     */
    public record Metric(String key, String label, Unit unit, String formula, List<String> inputs,
                         boolean rolledUp) {
    }

    public static final String REVENUE = "revenue";
    public static final String PAYMENTS_COLLECTED = "payments_collected";
    public static final String INVOICES_ISSUED = "invoices_issued";
    public static final String JOBS_COMPLETED = "jobs_completed";
    public static final String JOB_COST = "job_cost";
    public static final String JOB_PROFIT = "job_profit";
    public static final String LABOUR_MINUTES = "labour_minutes";
    public static final String EXPENSE_TOTAL = "expense_total";
    public static final String OUTSTANDING_RECEIVABLE = "outstanding_receivable";
    public static final String OVERDUE_AMOUNT = "overdue_amount";
    public static final String AVG_JOB_MARGIN_PERCENT = "avg_job_margin_percent";
    public static final String REPEAT_CUSTOMER_REVENUE = "repeat_customer_revenue";

    /** Metrics materialised per day (and per location) by the refresh job. */
    public static final List<String> ROLLED_UP = List.of(
            REVENUE, PAYMENTS_COLLECTED, INVOICES_ISSUED, JOBS_COMPLETED, JOB_COST,
            LABOUR_MINUTES, EXPENSE_TOTAL);

    private static final List<Metric> ALL = List.of(
            new Metric(REVENUE, "Revenue", Unit.CURRENCY,
                    "Sum of issued invoice totals, excluding void invoices, converted to the"
                            + " workspace currency at the rate frozen on each invoice.",
                    List.of("invoice.total", "invoice.base_total", "invoice.issue_date"), true),
            new Metric(PAYMENTS_COLLECTED, "Payments collected", Unit.CURRENCY,
                    "Sum of payments received in the period. A reversed payment is removed, so the"
                            + " figure always matches the payment ledger.",
                    List.of("payment.amount", "payment.paid_at"), true),
            new Metric(INVOICES_ISSUED, "Invoices issued", Unit.COUNT,
                    "Number of non-void invoices issued in the period.",
                    List.of("invoice"), true),
            new Metric(JOBS_COMPLETED, "Jobs completed", Unit.COUNT,
                    "Number of completed jobs whose delivery date (actual end, else scheduled end)"
                            + " falls in the period.",
                    List.of("job.status", "job.actual_end", "job.scheduled_end"), true),
            new Metric(JOB_COST, "Direct job cost", Unit.CURRENCY,
                    "Sum of the materialised cost roll-ups for jobs delivered in the period: labour,"
                            + " materials, equipment, subcontractors and other direct costs.",
                    List.of("job_cost"), true),
            new Metric(JOB_PROFIT, "Direct job profit", Unit.CURRENCY,
                    "Revenue of delivered jobs minus their direct job cost. Overheads are excluded, so"
                            + " this is not business net profit.",
                    List.of("invoice", "job_cost"), false),
            new Metric(LABOUR_MINUTES, "Labour minutes", Unit.MINUTES,
                    "Sum of closed work-log durations in the period.",
                    List.of("work_log"), true),
            new Metric(EXPENSE_TOTAL, "Expenses", Unit.CURRENCY,
                    "Sum of every recorded expense including business overheads, in the workspace"
                            + " currency.",
                    List.of("expense"), true),
            new Metric(OUTSTANDING_RECEIVABLE, "Outstanding receivable", Unit.CURRENCY,
                    "Point-in-time balance of non-void invoices at the end of the period.",
                    List.of("invoice.total", "invoice.amount_paid"), false),
            new Metric(OVERDUE_AMOUNT, "Overdue", Unit.CURRENCY,
                    "Portion of the outstanding balance whose due date has passed.",
                    List.of("invoice.due_date"), false),
            new Metric(AVG_JOB_MARGIN_PERCENT, "Average job margin", Unit.PERCENT,
                    "Mean of (job revenue - job cost) / job revenue across delivered jobs that have"
                            + " both revenue and cost. Jobs with unknown inputs are excluded and counted.",
                    List.of("job_cost", "invoice"), false),
            new Metric(REPEAT_CUSTOMER_REVENUE, "Repeat-customer revenue", Unit.CURRENCY,
                    "Revenue in the period from customers who have more than one non-void invoice in"
                            + " the trailing 12 months.",
                    List.of("invoice.customer_id"), false));

    public static List<Metric> all() {
        return ALL;
    }

    public static Metric require(String key) {
        return ALL.stream().filter(metric -> metric.key().equals(key)).findFirst()
                .orElseThrow(() -> com.operonix.common.error.ApiException.of(
                        com.operonix.common.error.ErrorCode.VALIDATION_FAILED,
                        "Unknown metric: " + key));
    }

    public static boolean exists(String key) {
        return ALL.stream().anyMatch(metric -> metric.key().equals(key));
    }
}
