package com.operonix.analytics;

import com.fasterxml.jackson.databind.JsonNode;
import com.operonix.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tax collected is owed to a tax authority, not earned. Revenue, job profit and margin are therefore
 * reported net of tax, and the costing panel has to agree with the analytics figures for the same job.
 */
class TaxExclusiveRevenueIT extends AbstractIntegrationTest {

    @Test
    void revenueAndMarginExcludeCollectedTax() throws Exception {
        Session owner = register("Harbor Tax Co", "owner@harbortax.example");
        UUID customer = createCustomer(owner, "Tax Aware Customer");
        UUID location = locationOf(owner, customer);
        UUID service = createService(owner, "Inspection visit", "100.00", "0.10");

        JsonNode quote = postJson("/api/v1/quotes", Map.of(
                "customerId", customer, "locationId", location,
                "lines", List.of(Map.of("serviceItemId", service, "quantity", 1))), owner, 201);
        UUID quoteId = UUID.fromString(quote.get("id").asText());
        postJson("/api/v1/quotes/" + quoteId + "/send", null, owner, 200);
        postJson("/api/v1/quotes/" + quoteId + "/accept", null, owner, 200);

        LocalDate today = LocalDate.now();
        JsonNode job = postJson("/api/v1/quotes/" + quoteId + "/convert", Map.of(
                "scheduledStart", today + "T09:00:00Z",
                "scheduledEnd", today + "T11:00:00Z",
                "technicianIds", List.of(owner.userId().toString())), owner, 201);
        UUID jobId = UUID.fromString(job.get("id").asText());

        // Cost has to be knowable before profit is reported at all.
        putJson("/api/v1/team/" + owner.userId(), Map.of("hourlyCost", 30), owner, 200);
        postJson("/api/v1/jobs/" + jobId + "/work-logs", Map.of(
                "startAt", today + "T09:00:00Z", "endAt", today + "T10:00:00Z"), owner, 201);
        postJson("/api/v1/jobs/" + jobId + "/transitions", Map.of("to", "IN_PROGRESS"), owner, 200);
        postJson("/api/v1/jobs/" + jobId + "/transitions", Map.of("to", "COMPLETED"), owner, 200);

        JsonNode invoice = postJson("/api/v1/jobs/" + jobId + "/invoice", Map.of(), owner, 201);
        UUID invoiceId = UUID.fromString(invoice.get("id").asText());
        postJson("/api/v1/invoices/" + invoiceId + "/send", null, owner, 200);

        // The customer is billed 110.00: 100.00 of work plus 10.00 of tax.
        assertDecimal(invoice.get("subtotal"), "100.00");
        assertDecimal(invoice.get("taxAmount"), "10.00");
        assertDecimal(invoice.get("total"), "110.00");

        postJson("/api/v1/analytics/refresh", Map.of(), owner, 200);
        JsonNode overview = getJson("/api/v1/analytics/overview", owner, 200);

        // Tax is not revenue; profit is measured on the net: 100.00 - 30.00 of labour.
        assertDecimal(metric(overview, "revenue"), "100.00");
        assertDecimal(metric(overview, "job_profit"), "70.00");
        assertDecimal(metric(overview, "avg_job_margin_percent"), "70.0");

        // The costing panel must agree with the analytics figure for the same job.
        JsonNode profitability = getJson("/api/v1/jobs/" + jobId + "/profitability", owner, 200);
        assertThat(profitability.get("reasons")).isEmpty();
        assertDecimal(profitability.get("revenue"), "100.00");
        assertDecimal(profitability.get("directProfit"), "70.00");
        assertDecimal(profitability.get("plannedRevenue"), "100.00");

        // What the customer owes is a balance, and a balance does include tax.
        assertDecimal(metric(overview, "outstanding_receivable"), "110.00");

        // Collecting it changes cash, not revenue.
        postJson("/api/v1/invoices/" + invoiceId + "/payments",
                Map.of("amount", 110, "method", "CASH"), owner, 201);
        postJson("/api/v1/analytics/refresh", Map.of(), owner, 200);
        JsonNode afterPayment = getJson("/api/v1/analytics/overview", owner, 200);
        assertDecimal(metric(afterPayment, "revenue"), "100.00");
        assertDecimal(metric(afterPayment, "payments_collected"), "110.00");

        JsonNode byService = getJson("/api/v1/analytics/breakdown?dimension=SERVICE", owner, 200);
        assertDecimal(byService.get("rows").get(0).get("revenue"), "100.00");
        JsonNode byCustomer = getJson("/api/v1/analytics/breakdown?dimension=CUSTOMER", owner, 200);
        assertDecimal(byCustomer.get("rows").get(0).get("revenue"), "100.00");
    }

    private static JsonNode metric(JsonNode overview, String key) {
        for (JsonNode metric : overview.get("metrics")) {
            if (key.equals(metric.get("key").asText())) {
                return metric.get("value");
            }
        }
        throw new AssertionError("Metric not present: " + key);
    }

    private static void assertDecimal(JsonNode node, String expected) {
        assertThat(node.isNull() ? null : node.decimalValue()).isEqualByComparingTo(expected);
    }
}