package com.operonix.ai;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two rules that keep the AI trustworthy are pure functions, so they are tested without a
 * database or a provider.
 */
class AiGuardTest {

    private static final String PAYLOAD = "{\"revenue\":\"1234.50\",\"jobs\":3,\"currency\":\"USD\"}";

    @Test
    void narrativesThatUseOnlySuppliedFiguresAreAccepted() {
        assertThat(AiService.grounded("Revenue was 1234.50 USD across 3 jobs.", PAYLOAD)).isTrue();
        assertThat(AiService.grounded("Revenue rose 12% this month across 3 jobs.", PAYLOAD)).isTrue();
        assertThat(AiService.grounded("Revenue was 1234.50 USD; no comparison is available.", PAYLOAD))
                .isTrue();
    }

    @Test
    void narrativesThatInventFiguresAreRejected() {
        // 9999.99 was never calculated by the server.
        assertThat(AiService.grounded("Revenue was 9999.99 USD.", PAYLOAD)).isFalse();
        assertThat(AiService.grounded("", PAYLOAD)).isFalse();
    }

    @Test
    void questionsResolveToCatalogueMetricsAndSaneRanges() {
        LocalDate today = LocalDate.of(2026, 5, 20);

        AiIntent revenue = AiIntent.resolve("How much revenue did we make last month?", today);
        assertThat(revenue.metrics()).contains("revenue");
        assertThat(revenue.from()).isEqualTo(LocalDate.of(2026, 4, 1));
        assertThat(revenue.to()).isEqualTo(LocalDate.of(2026, 4, 30));
        assertThat(revenue.matched()).isTrue();

        AiIntent overdue = AiIntent.resolve("Who owes us money? Show overdue", today);
        assertThat(overdue.metrics()).contains("outstanding_receivable", "overdue_amount");

        AiIntent byLocation = AiIntent.resolve("Revenue by location this quarter", today);
        assertThat(byLocation.dimension()).isEqualTo("LOCATION");
        assertThat(byLocation.from()).isEqualTo(LocalDate.of(2026, 4, 1));

        AiIntent lastDays = AiIntent.resolve("revenue last 7 days", today);
        assertThat(lastDays.from()).isEqualTo(today.minusDays(6));

        // Nothing recognised: a default overview, clearly marked as unmatched.
        AiIntent unknown = AiIntent.resolve("hello there", today);
        assertThat(unknown.matched()).isFalse();
        assertThat(unknown.metrics()).isNotEmpty();
    }

    @Test
    void onlyCatalogueMetricsCanBeRequested() {
        AiIntent intent = AiIntent.resolve("show me everything", LocalDate.of(2026, 5, 20));
        assertThat(com.operonix.analytics.MetricDefinition.all().stream()
                .map(com.operonix.analytics.MetricDefinition.Metric::key)
                .toList()).containsAll(intent.metrics());
    }
}
