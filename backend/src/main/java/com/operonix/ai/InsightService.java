package com.operonix.ai;

import com.operonix.analytics.AnalyticsDtos;
import com.operonix.analytics.AnalyticsService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Operational insights derived only from verified figures.
 *
 * <p>Deterministic by design: an insight is a rule over calculated metrics ("revenue fell more than
 * 10% against the previous period", "this much is overdue"), so it cannot be hallucinated. The AI
 * layer can narrate these later, but the trigger itself is auditable code.
 */
@Service
public class InsightService {

    private final AnalyticsService analytics;

    public InsightService(AnalyticsService analytics) {
        this.analytics = analytics;
    }

    @Transactional
    public AiDtos.InsightsResponse insights() {
        AnalyticsDtos.Overview overview = analytics.overview(null, null, null);
        AnalyticsDtos.Ageing ageing = analytics.ageing();
        AnalyticsDtos.JobProfitability jobs = analytics.jobProfitability(null, null, null);
        String currency = overview.currency();
        List<AiDtos.Insight> insights = new ArrayList<>();

        for (AnalyticsDtos.MetricValue metric : overview.metrics()) {
            if ("revenue".equals(metric.key()) && metric.changePercent() != null) {
                double change = metric.changePercent().doubleValue();
                if (change <= -10) {
                    insights.add(new AiDtos.Insight("WARNING", "Revenue is down "
                            + String.format(java.util.Locale.ROOT, "%.1f", Math.abs(change)) + "%",
                            "Issued revenue fell against the previous period of the same length.",
                            "revenue", "/analytics"));
                } else if (change >= 10) {
                    insights.add(new AiDtos.Insight("POSITIVE", "Revenue is up "
                            + String.format(java.util.Locale.ROOT, "%.1f", change) + "%",
                            "Issued revenue grew against the previous period of the same length.",
                            "revenue", "/analytics"));
                }
            }
        }

        if (ageing.overdue().signum() > 0) {
            insights.add(new AiDtos.Insight("WARNING", "Overdue receivables",
                    "Invoices past their due date total " + ageing.overdue().toPlainString() + " "
                            + currency + ".", "overdue_amount", "/invoices"));
        }

        if (!jobs.rows().isEmpty() && jobs.incompleteCount() > 0) {
            insights.add(new AiDtos.Insight("INFO", "Job costing has gaps",
                    jobs.incompleteCount() + " delivered job(s) cannot be costed because a rate, a time"
                            + " entry or an invoice is missing.", "job_profit", "/schedule"));
        }

        if (jobs.rows().size() >= 3 && jobs.totalRevenue().signum() > 0) {
            BigDecimal margin = jobs.totalProfit().multiply(BigDecimal.valueOf(100))
                    .divide(jobs.totalRevenue(), 1, java.math.RoundingMode.HALF_UP);
            insights.add(new AiDtos.Insight(margin.signum() < 0 ? "WARNING" : "INFO",
                    "Direct margin " + margin.stripTrailingZeros().toPlainString() + "%",
                    "Across " + jobs.rows().size() + " delivered job(s) with complete cost data.",
                    "avg_job_margin_percent", "/analytics"));
        }

        for (AnalyticsDtos.MetricValue metric : overview.metrics()) {
            if ("repeat_customer_revenue".equals(metric.key()) && metric.available()
                    && metric.value() != null) {
                BigDecimal revenue = overview.metrics().stream()
                        .filter(value -> "revenue".equals(value.key()) && value.value() != null)
                        .map(AnalyticsDtos.MetricValue::value).findFirst().orElse(BigDecimal.ZERO);
                if (revenue.signum() > 0) {
                    BigDecimal share = metric.value().multiply(BigDecimal.valueOf(100))
                            .divide(revenue, 0, java.math.RoundingMode.HALF_UP);
                    insights.add(new AiDtos.Insight("INFO",
                            "Repeat customers are " + share.toPlainString() + "% of revenue",
                            "Revenue from customers with more than one invoice in the last year.",
                            "repeat_customer_revenue", "/analytics"));
                }
            }
        }

        if (insights.isEmpty()) {
            insights.add(new AiDtos.Insight("INFO", "Nothing unusual",
                    "No material trend, overdue balance or costing gap was found for the last 30 days.",
                    null, "/analytics"));
        }

        List<String> notes = List.of(
                "Insights are rules over figures calculated by Operonix, not generative text.",
                "They describe the last 30 days (" + LocalDate.now() + " inclusive).");
        return new AiDtos.InsightsResponse(currency, overview.from(), overview.to(), insights, notes);
    }
}
