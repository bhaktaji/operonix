package com.operonix.ai;

import com.operonix.analytics.MetricDefinition;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a natural-language question into a validated metric request.
 *
 * <p>Deliberately deterministic rather than model-driven. A language model guessing which table to
 * read is exactly the failure mode this design removes: the question is mapped onto the same
 * whitelisted metrics and dimensions the dashboard uses, and anything not in the catalogue cannot be
 * requested. The model's only job is to narrate the result.
 *
 * @param metrics   catalogue keys, never empty
 * @param dimension optional breakdown dimension (LOCATION/SERVICE/CUSTOMER/TECHNICIAN)
 * @param groupBy   day, week or month
 * @param matched   false when no keyword matched and the default overview was used
 */
public record AiIntent(List<String> metrics, LocalDate from, LocalDate to, String dimension,
                       String groupBy, boolean matched, String topic) {

    private static final Pattern LAST_N = Pattern.compile(
            "last\\s+(\\d{1,2})\\s+(day|days|week|weeks|month|months|year|years)");

    private static final List<KeywordMetric> KEYWORDS = List.of(
            new KeywordMetric(List.of("revenue", "sales", "turnover", "income", "billing"),
                    List.of(MetricDefinition.REVENUE, MetricDefinition.INVOICES_ISSUED), "revenue"),
            new KeywordMetric(List.of("profit", "margin", "profitability"),
                    List.of(MetricDefinition.JOB_PROFIT, MetricDefinition.AVG_JOB_MARGIN_PERCENT,
                            MetricDefinition.REVENUE), "profitability"),
            new KeywordMetric(List.of("outstanding", "unpaid", "owed", "receivable", "debt", "overdue"),
                    List.of(MetricDefinition.OUTSTANDING_RECEIVABLE, MetricDefinition.OVERDUE_AMOUNT),
                    "collections"),
            new KeywordMetric(List.of("collected", "cash", "payments", "paid", "receipts"),
                    List.of(MetricDefinition.PAYMENTS_COLLECTED, MetricDefinition.OUTSTANDING_RECEIVABLE),
                    "collections"),
            new KeywordMetric(List.of("expense", "spend", "spent", "cost", "costs"),
                    List.of(MetricDefinition.EXPENSE_TOTAL, MetricDefinition.JOB_COST), "costs"),
            new KeywordMetric(List.of("job", "jobs", "work order", "completed"),
                    List.of(MetricDefinition.JOBS_COMPLETED, MetricDefinition.JOB_PROFIT), "operations"),
            new KeywordMetric(List.of("hour", "hours", "labour", "labor", "time", "productivity"),
                    List.of(MetricDefinition.LABOUR_MINUTES, MetricDefinition.JOBS_COMPLETED),
                    "productivity"),
            new KeywordMetric(List.of("repeat", "retention", "loyal", "recurring customer"),
                    List.of(MetricDefinition.REPEAT_CUSTOMER_REVENUE, MetricDefinition.REVENUE),
                    "customers"),
            new KeywordMetric(List.of("customer", "customers", "clients"),
                    List.of(MetricDefinition.REVENUE, MetricDefinition.REPEAT_CUSTOMER_REVENUE),
                    "customers"));

    private record KeywordMetric(List<String> keywords, List<String> metrics, String topic) {
    }

    public static AiIntent resolve(String question, LocalDate today) {
        String text = question == null ? "" : question.toLowerCase(Locale.ROOT);

        Set<String> metrics = new LinkedHashSet<>();
        String topic = "overview";
        for (KeywordMetric keyword : KEYWORDS) {
            if (keyword.keywords().stream().anyMatch(text::contains)) {
                metrics.addAll(keyword.metrics());
                if ("overview".equals(topic)) {
                    topic = keyword.topic();
                }
            }
        }
        boolean matched = !metrics.isEmpty();
        if (!matched) {
            metrics.addAll(List.of(MetricDefinition.REVENUE, MetricDefinition.JOB_PROFIT,
                    MetricDefinition.OUTSTANDING_RECEIVABLE));
        }

        LocalDate from;
        LocalDate to;
        Matcher lastN = LAST_N.matcher(text);
        if (text.contains("today")) {
            from = today;
            to = today;
        } else if (text.contains("yesterday")) {
            from = today.minusDays(1);
            to = from;
        } else if (text.contains("this week")) {
            from = today.with(DayOfWeek.MONDAY);
            to = today;
        } else if (text.contains("last week")) {
            to = today.with(DayOfWeek.MONDAY).minusDays(1);
            from = to.minusDays(6);
        } else if (text.contains("this month")) {
            from = today.withDayOfMonth(1);
            to = today;
        } else if (text.contains("last month")) {
            to = today.withDayOfMonth(1).minusDays(1);
            from = to.withDayOfMonth(1);
        } else if (text.contains("this quarter")) {
            int firstMonth = ((today.getMonthValue() - 1) / 3) * 3 + 1;
            from = LocalDate.of(today.getYear(), firstMonth, 1);
            to = today;
        } else if (text.contains("last quarter")) {
            int firstMonth = ((today.getMonthValue() - 1) / 3) * 3 + 1;
            LocalDate quarterStart = LocalDate.of(today.getYear(), firstMonth, 1);
            to = quarterStart.minusDays(1);
            from = quarterStart.minusMonths(3);
        } else if (text.contains("this year") || text.contains("year to date") || text.contains("ytd")) {
            from = LocalDate.of(today.getYear(), 1, 1);
            to = today;
        } else if (text.contains("last year")) {
            from = LocalDate.of(today.getYear() - 1, 1, 1);
            to = LocalDate.of(today.getYear() - 1, 12, 31);
        } else if (lastN.find()) {
            int amount = Integer.parseInt(lastN.group(1));
            String unit = lastN.group(2);
            int days = unit.startsWith("day") ? amount
                    : unit.startsWith("week") ? amount * 7
                    : unit.startsWith("month") ? amount * 30
                    : amount * 365;
            from = today.minusDays(days - 1L);
            to = today;
        } else {
            from = today.minusDays(29);
            to = today;
        }

        String dimension = null;
        if (text.contains("by location") || text.contains("per location") || text.contains("branch")
                || text.contains("by site")) {
            dimension = "LOCATION";
        } else if (text.contains("by service") || text.contains("per service")
                || text.contains("by product")) {
            dimension = "SERVICE";
        } else if (text.contains("by customer") || text.contains("per customer")
                || text.contains("top customer") || text.contains("best customer")) {
            dimension = "CUSTOMER";
        } else if (text.contains("by technician") || text.contains("by tech")
                || text.contains("by team") || text.contains("by staff") || text.contains("by employee")) {
            dimension = "TECHNICIAN";
        }

        String groupBy = text.contains("monthly") || text.contains("by month") ? "month"
                : text.contains("weekly") || text.contains("by week") ? "week"
                : "day";

        // Only catalogue metrics survive, in catalogue order.
        List<String> ordered = new ArrayList<>(MetricDefinition.all().stream()
                .map(MetricDefinition.Metric::key).filter(metrics::contains).toList());
        return new AiIntent(List.copyOf(ordered), from, to, dimension, groupBy, matched, topic);
    }
}
