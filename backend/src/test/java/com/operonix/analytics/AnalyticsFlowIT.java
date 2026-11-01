package com.operonix.analytics;

import com.fasterxml.jackson.databind.JsonNode;
import com.operonix.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2a: the dashboards must reconcile with the records they summarise, and must be honest about what
 * they cannot know.
 */
class AnalyticsFlowIT extends AbstractIntegrationTest {

    @Test
    void dashboardReconcilesWithSourceRecordsAndHidesUnknownProfit() throws Exception {
        Session owner = register("Meridian Field Services", "owner@meridian.example");
        UUID customer = createCustomer(owner, "Cedar Park Group");
        UUID location = locationOf(owner, customer);
        UUID service = createService(owner, "Quarterly inspection", "100.00", "0");

        JsonNode quote = postJson("/api/v1/quotes", Map.of(
                "customerId", customer, "locationId", location,
                "lines", List.of(Map.of("serviceItemId", service, "quantity", 2))), owner, 201);
        UUID quoteId = UUID.fromString(quote.get("id").asText());
        postJson("/api/v1/quotes/" + quoteId + "/send", null, owner, 200);
        postJson("/api/v1/quotes/" + quoteId + "/accept", null, owner, 200);

        LocalDate today = LocalDate.now();
        JsonNode job = postJson("/api/v1/quotes/" + quoteId + "/convert", Map.of(
                "scheduledStart", today + "T09:00:00Z",
                "scheduledEnd", today + "T12:00:00Z",
                "technicianIds", List.of(owner.userId())), owner, 201);
        UUID jobId = UUID.fromString(job.get("id").asText());

        // Labour is priced, and the timer is closed, so the job's cost is knowable.
        putJson("/api/v1/team/" + owner.userId(), Map.of("hourlyCost", 30), owner, 200);
        postJson("/api/v1/jobs/" + jobId + "/work-logs", Map.of(
                "startAt", today + "T09:00:00Z", "endAt", today + "T10:00:00Z"), owner, 201);

        postJson("/api/v1/jobs/" + jobId + "/transitions", Map.of("to", "IN_PROGRESS"), owner, 200);
        postJson("/api/v1/jobs/" + jobId + "/transitions", Map.of("to", "COMPLETED"), owner, 200);

        JsonNode invoice = postJson("/api/v1/jobs/" + jobId + "/invoice", Map.of(), owner, 201);
        UUID invoiceId = UUID.fromString(invoice.get("id").asText());
        postJson("/api/v1/invoices/" + invoiceId + "/send", null, owner, 200);
        postJson("/api/v1/invoices/" + invoiceId + "/payments",
                Map.of("amount", 50, "method", "CASH"), owner, 201);

        postJson("/api/v1/analytics/refresh", Map.of(), owner, 200);

        JsonNode overview = getJson("/api/v1/analytics/overview", owner, 200);
        assertThat(overview.get("currency").asText()).isEqualTo("USD");
        assertDecimal(metric(overview, "revenue"), "200.00");
        assertDecimal(metric(overview, "payments_collected"), "50.00");
        assertDecimal(metric(overview, "invoices_issued"), "1");
        assertDecimal(metric(overview, "jobs_completed"), "1");
        assertDecimal(metric(overview, "labour_minutes"), "60");
        assertDecimal(metric(overview, "job_cost"), "30.00");
        assertDecimal(metric(overview, "job_profit"), "170.00");
        assertDecimal(metric(overview, "outstanding_receivable"), "150.00");
        assertDecimal(metric(overview, "overdue_amount"), "0.00");
        assertDecimal(metric(overview, "avg_job_margin_percent"), "85.0");

        // The drill-down must add back up to the headline figure.
        JsonNode jobs = getJson("/api/v1/analytics/jobs", owner, 200);
        assertThat(jobs.get("rows").size()).isEqualTo(1);
        JsonNode jobRow = jobs.get("rows").get(0);
        assertThat(jobRow.get("id").asText()).isEqualTo(jobId.toString());
        assertThat(jobRow.get("complete").asBoolean()).isTrue();
        assertDecimal(jobRow.get("profit"), "170.00");
        assertDecimal(jobs.get("totalRevenue"), "200.00");

        JsonNode byService = getJson("/api/v1/analytics/breakdown?dimension=SERVICE", owner, 200);
        assertThat(byService.get("rows").get(0).get("label").asText()).isEqualTo("Quarterly inspection");
        assertDecimal(byService.get("rows").get(0).get("revenue"), "200.00");

        JsonNode byLocation = getJson("/api/v1/analytics/breakdown?dimension=LOCATION", owner, 200);
        assertThat(byLocation.get("rows").size()).isEqualTo(1);
        assertDecimal(byLocation.get("rows").get(0).get("revenue"), "200.00");

        JsonNode trend = getJson("/api/v1/analytics/trend?metric=revenue&granularity=day", owner, 200);
        assertThat(trend.get("available").asBoolean()).isTrue();
        assertDecimal(trend.get("points").get(trend.get("points").size() - 1).get("value"), "200.00");

        JsonNode ageing = getJson("/api/v1/analytics/ageing", owner, 200);
        assertDecimal(ageing.get("outstanding"), "150.00");
        assertThat(ageing.get("invoices").size()).isEqualTo(1);
        assertThat(ageing.get("invoices").get(0).get("number").asText())
                .startsWith("INV-");

        // Re-running the rollup is idempotent: no metric doubles.
        postJson("/api/v1/analytics/refresh", Map.of(), owner, 200);
        assertDecimal(metric(getJson("/api/v1/analytics/overview", owner, 200), "revenue"), "200.00");
    }

    @Test
    void unavailableProfitIsReportedAsUnknownAndAnalyticsStayTenantScoped() throws Exception {
        Session alpha = register("Alpha Insights", "owner@alphainsights.example");
        Session beta = register("Beta Insights", "owner@betainsights.example");
        UUID customer = createCustomer(alpha, "Alpha Client");
        UUID location = locationOf(alpha, customer);

        LocalDate today = LocalDate.now();
        JsonNode job = postJson("/api/v1/jobs", Map.of(
                "customerId", customer, "locationId", location, "title", "Unpriced visit",
                "scheduledStart", today + "T13:00:00Z",
                "scheduledEnd", today + "T15:00:00Z",
                "technicianIds", List.of(alpha.userId())), alpha, 201);
        UUID jobId = UUID.fromString(job.get("id").asText());
        postJson("/api/v1/jobs/" + jobId + "/transitions", Map.of("to", "IN_PROGRESS"), alpha, 200);
        postJson("/api/v1/jobs/" + jobId + "/transitions", Map.of("to", "COMPLETED"), alpha, 200);

        // Delivered with no invoice and no priced labour: profit must be null, not zero.
        postJson("/api/v1/analytics/refresh", Map.of(), alpha, 200);
        JsonNode overview = getJson("/api/v1/analytics/overview", alpha, 200);
        JsonNode profit = metricNode(overview, "job_profit");
        assertThat(profit.get("available").asBoolean()).isFalse();
        assertThat(profit.path("value").isMissingNode()).isTrue();
        assertThat(profit.get("reason").asText()).contains("incomplete cost inputs");

        JsonNode jobs = getJson("/api/v1/analytics/jobs", alpha, 200);
        assertThat(jobs.get("incompleteCount").asInt()).isEqualTo(1);
        assertThat(jobs.get("rows").get(0).get("complete").asBoolean()).isFalse();
        assertThat(jobs.get("rows").get(0).get("reasons").toString()).contains("No invoice raised");

        // Another workspace sees its own (empty) numbers, never Alpha's.
        JsonNode betaOverview = getJson("/api/v1/analytics/overview", beta, 200);
        assertDecimal(metric(betaOverview, "revenue"), "0.00");
        assertDecimal(metric(betaOverview, "jobs_completed"), "0");

        // Field staff cannot read workspace-wide performance.
        postJson("/api/v1/team", Map.of(
                "fullName", "Tess Field", "email", "tess@alphainsights.example",
                "password", "CorrectHorse9Battery", "role", "TECHNICIAN"), alpha, 201);
        Session tech = login("tess@alphainsights.example", "CorrectHorse9Battery");
        getJson("/api/v1/analytics/overview", tech, 403);
    }

    private static JsonNode metricNode(JsonNode overview, String key) {
        for (JsonNode metric : overview.get("metrics")) {
            if (key.equals(metric.get("key").asText())) {
                return metric;
            }
        }
        throw new AssertionError("Metric not present: " + key);
    }

    private static JsonNode metric(JsonNode overview, String key) {
        JsonNode node = metricNode(overview, key).get("value");
        if (node == null) {
            throw new AssertionError("Metric has no value: " + key);
        }
        return node;
    }

    private void assertDecimal(JsonNode node, String expected) {
        assertThat(node.decimalValue()).isEqualByComparingTo(new BigDecimal(expected));
    }
}
