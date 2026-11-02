package com.operonix.analytics;

import com.fasterxml.jackson.databind.JsonNode;
import com.operonix.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

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

    /**
     * The analytics page fires overview, trend and breakdown together on a cold rollup, so several
     * requests try to rebuild the same rows. They used to collide on uq_metric_daily and turn the
     * page into a 409; they must all answer, and leave exactly one row per metric, dimension and day.
     */
    @Test
    void parallelAnalyticsCallsShareOneRollupInsteadOfFailing() throws Exception {
        Session owner = register("Parallel Metrics Co", "owner@parallelmetrics.example");
        UUID customer = createCustomer(owner, "Northgate Facilities");
        UUID location = locationOf(owner, customer);
        UUID service = createService(owner, "Monthly inspection", "150.00", "0");

        JsonNode quote = postJson("/api/v1/quotes", Map.of(
                "customerId", customer, "locationId", location,
                "lines", List.of(Map.of("serviceItemId", service, "quantity", 1))), owner, 201);
        UUID quoteId = UUID.fromString(quote.get("id").asText());
        postJson("/api/v1/quotes/" + quoteId + "/send", null, owner, 200);
        postJson("/api/v1/quotes/" + quoteId + "/accept", null, owner, 200);

        LocalDate today = LocalDate.now();
        JsonNode job = postJson("/api/v1/quotes/" + quoteId + "/convert", Map.of(
                "scheduledStart", today + "T09:00:00Z",
                "scheduledEnd", today + "T10:00:00Z",
                "technicianIds", List.of(owner.userId())), owner, 201);
        UUID jobId = UUID.fromString(job.get("id").asText());
        postJson("/api/v1/jobs/" + jobId + "/transitions", Map.of("to", "IN_PROGRESS"), owner, 200);
        postJson("/api/v1/jobs/" + jobId + "/transitions", Map.of("to", "COMPLETED"), owner, 200);
        postJson("/api/v1/jobs/" + jobId + "/invoice", Map.of(), owner, 201);

        String[] paths = {
                "/api/v1/analytics/overview",
                "/api/v1/analytics/trend?metric=revenue&granularity=day",
                "/api/v1/analytics/breakdown?dimension=LOCATION"
        };
        ExecutorService pool = Executors.newFixedThreadPool(9);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        try {
            for (int i = 0; i < 9; i++) {
                String path = paths[i % paths.length];
                results.add(pool.submit(() -> {
                    gate.await();
                    return mvc.perform(get(path)
                                    .header("Authorization", "Bearer " + owner.accessToken()))
                            .andReturn().getResponse().getStatus();
                }));
            }
            gate.countDown();
            for (Future<Integer> result : results) {
                assertThat(result.get(60, TimeUnit.SECONDS)).isEqualTo(200);
            }
        } finally {
            pool.shutdownNow();
        }

        JsonNode overview = getJson("/api/v1/analytics/overview", owner, 200);
        assertDecimal(metric(overview, "revenue"), "150.00");
        assertDecimal(metric(overview, "jobs_completed"), "1");
    }

    /**
     * A workspace can issue an invoice seconds after a refresh. The time-based freshness window used
     * to consider that rollup "fresh" and report revenue that omitted the new invoice, so the
     * dashboard contradicted the profit table on the same page.
     */
    @Test
    void aWriteSinceTheLastRefreshRebuildsTheRollupWithoutAnExplicitRefresh() throws Exception {
        Session owner = register("Freshness Metrics Co", "owner@freshnessmetrics.example");
        UUID customer = createCustomer(owner, "Granite Bay Offices");
        UUID location = locationOf(owner, customer);
        UUID service = createService(owner, "Washroom supply audit", "120.00", "0");
        LocalDate today = LocalDate.now();

        UUID firstJob = completedJob(owner, customer, location, service, today, "T09:00:00Z");
        postJson("/api/v1/jobs/" + firstJob + "/invoice", Map.of(), owner, 201);

        // Refresh, then write again inside the freshness window.
        postJson("/api/v1/analytics/refresh", Map.of(), owner, 200);
        assertDecimal(metric(getJson("/api/v1/analytics/overview", owner, 200), "revenue"), "120.00");

        UUID secondJob = completedJob(owner, customer, location, service, today, "T13:00:00Z");
        postJson("/api/v1/jobs/" + secondJob + "/invoice", Map.of(), owner, 201);

        // No refresh call here: the read path must notice the invoice and rebuild.
        JsonNode overview = getJson("/api/v1/analytics/overview", owner, 200);
        assertDecimal(metric(overview, "revenue"), "240.00");
        assertDecimal(metric(overview, "invoices_issued"), "2");
        assertThat(getJson("/api/v1/analytics/jobs", owner, 200).get("rows").size()).isEqualTo(2);
    }

    private UUID completedJob(Session owner, UUID customer, UUID location, UUID service, LocalDate day,
                             String startTime) throws Exception {
        JsonNode quote = postJson("/api/v1/quotes", Map.of(
                "customerId", customer, "locationId", location,
                "lines", List.of(Map.of("serviceItemId", service, "quantity", 1))), owner, 201);
        UUID quoteId = UUID.fromString(quote.get("id").asText());
        postJson("/api/v1/quotes/" + quoteId + "/send", null, owner, 200);
        postJson("/api/v1/quotes/" + quoteId + "/accept", null, owner, 200);
        int hour = Integer.parseInt(startTime.substring(1, 3)) + 1;
        String endTime = "T" + String.format("%02d", hour) + ":00:00Z";
        JsonNode job = postJson("/api/v1/quotes/" + quoteId + "/convert", Map.of(
                "scheduledStart", day + startTime,
                "scheduledEnd", day + endTime,
                "technicianIds", List.of(owner.userId())), owner, 201);
        UUID jobId = UUID.fromString(job.get("id").asText());
        postJson("/api/v1/jobs/" + jobId + "/transitions", Map.of("to", "IN_PROGRESS"), owner, 200);
        postJson("/api/v1/jobs/" + jobId + "/transitions", Map.of("to", "COMPLETED"), owner, 200);
        return jobId;
    }

    private void assertDecimal(JsonNode node, String expected) {
        assertThat(node.decimalValue()).isEqualByComparingTo(new BigDecimal(expected));
    }
}
