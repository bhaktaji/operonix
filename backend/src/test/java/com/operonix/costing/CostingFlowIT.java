package com.operonix.costing;

import com.fasterxml.jackson.databind.JsonNode;
import com.operonix.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1b: labour, expenses, vendors, roll-up idempotency and the complete/incomplete contract.
 *
 * <p>Every scenario asserts on the API, so it also proves the endpoints are wired and tenant-scoped
 * rather than only that the service compiles.
 */
class CostingFlowIT extends AbstractIntegrationTest {

    @Test
    void costsRollUpAndProfitabilityStaysIncompleteUntilEveryInputExists() throws Exception {
        Session owner = register("Northwind Services", "owner@northwind.example");
        UUID customer = createCustomer(owner, "Harbor Facilities");
        UUID location = locationOf(owner, customer);
        UUID service = createService(owner, "Scheduled maintenance", "200.00", "0");

        // Planned cost turns the catalogue item into a margin input.
        putJson("/api/v1/services/" + service, Map.of(
                "name", "Scheduled maintenance", "unit", "VISIT", "basePrice", 200,
                "durationMinutes", 180, "taxRate", 0, "cost", 60, "active", true), owner, 200);

        JsonNode quote = postJson("/api/v1/quotes", Map.of(
                "customerId", customer, "locationId", location,
                "lines", List.of(Map.of("serviceItemId", service, "quantity", 2))), owner, 201);
        UUID quoteId = UUID.fromString(quote.get("id").asText());
        postJson("/api/v1/quotes/" + quoteId + "/send", null, owner, 200);
        postJson("/api/v1/quotes/" + quoteId + "/accept", null, owner, 200);
        JsonNode job = postJson("/api/v1/quotes/" + quoteId + "/convert", Map.of(
                "scheduledStart", "2027-05-04T09:00:00Z",
                "scheduledEnd", "2027-05-04T12:00:00Z",
                "technicianIds", List.of(owner.userId())), owner, 201);
        UUID jobId = UUID.fromString(job.get("id").asText());

        // Labour rate is configuration, not a default.
        putJson("/api/v1/team/" + owner.userId(), Map.of("hourlyCost", 40), owner, 200);

        postJson("/api/v1/jobs/" + jobId + "/work-logs", Map.of(
                "startAt", "2027-05-04T09:00:00Z", "endAt", "2027-05-04T11:30:00Z"), owner, 201);

        UUID vendor = UUID.fromString(postJson("/api/v1/vendors",
                Map.of("name", "Harbor Supplies", "defaultRate", 0, "paymentTerms", "NET_15"),
                owner, 201).get("id").asText());
        postJson("/api/v1/vendors", Map.of("name", "harbor supplies"), owner, 409);

        postJson("/api/v1/jobs/" + jobId + "/expenses", Map.of(
                "description", "Replacement filters", "amount", 50, "currency", "USD",
                "expenseDate", "2027-05-04", "category", "MATERIAL", "vendorId", vendor), owner, 201);

        // A foreign-currency cost with no rate is refused instead of being silently guessed.
        JsonNode missingRate = postJson("/api/v1/jobs/" + jobId + "/expenses", Map.of(
                "description", "Imported part", "amount", 900, "currency", "EUR",
                "expenseDate", "2027-05-04", "category", "MATERIAL"), owner, 422);
        assertThat(missingRate.get("code").asText()).isEqualTo("FX_RATE_MISSING");

        postJson("/api/v1/exchange-rates", Map.of(
                "base", "EUR", "quote", "USD", "rate", 1.10, "asOf", "2027-05-04"), owner, 201);
        JsonNode euroExpense = postJson("/api/v1/jobs/" + jobId + "/expenses", Map.of(
                "description", "Imported part", "amount", 100, "currency", "EUR",
                "expenseDate", "2027-05-04", "category", "SUBCONTRACTOR"), owner, 201);
        assertDecimal(euroExpense.get("amount"), "100.00");
        assertDecimal(euroExpense.get("workspaceAmount"), "110.00");
        assertDecimal(euroExpense.get("fxRate"), "1.1");

        JsonNode view = getJson("/api/v1/jobs/" + jobId + "/costing", owner, 200);
        JsonNode cost = view.get("cost");
        assertDecimal(cost.get("laborCost"), "100.00");          // 2.5 h × 40
        assertDecimal(cost.get("materialCost"), "50.00");
        assertDecimal(cost.get("subcontractorCost"), "110.00");
        assertDecimal(cost.get("totalCost"), "260.00");
        assertDecimal(view.get("profitability").get("plannedCost"), "120.00"); // 2 × 60

        // No invoice yet: revenue is unknown, so profit must not be reported as a loss. Null amounts
        // are omitted from the JSON (non-null serialisation), so "absent" is the unknown signal.
        JsonNode beforeInvoice = view.get("profitability");
        assertThat(beforeInvoice.get("status").asText()).isEqualTo("INCOMPLETE");
        assertThat(beforeInvoice.path("directProfit").isMissingNode()).isTrue();
        assertThat(beforeInvoice.path("revenue").isMissingNode()).isTrue();

        JsonNode invoice = postJson("/api/v1/jobs/" + jobId + "/invoice", Map.of(), owner, 201);
        assertDecimal(invoice.get("total"), "400.00");

        JsonNode complete = getJson("/api/v1/jobs/" + jobId + "/profitability", owner, 200);
        assertThat(complete.get("status").asText()).isEqualTo("COMPLETE");
        assertDecimal(complete.get("revenue"), "400.00");
        assertDecimal(complete.get("directProfit"), "140.00");
        assertDecimal(complete.get("marginPercent"), "35.00");
        assertDecimal(complete.get("plannedRevenue"), "400.00");
        assertDecimal(complete.get("plannedMargin"), "280.00");
        assertThat(complete.get("actualMinutes").asLong()).isEqualTo(150);
        assertThat(complete.get("scheduledMinutes").asLong()).isEqualTo(180);
        assertThat(complete.get("scopeNote").asText()).contains("Direct job profit only");

        // Recomputing is idempotent: the roll-up is derived, never accumulated.
        for (int i = 0; i < 3; i++) {
            JsonNode recomputed = postJson("/api/v1/jobs/" + jobId + "/costs/recompute", null, owner, 200);
            assertDecimal(recomputed.get("totalCost"), "260.00");
        }

        // An open time log makes labour an estimate, so profitability becomes incomplete again.
        postJson("/api/v1/jobs/" + jobId + "/work-logs", Map.of("startAt", "2027-05-05T09:00:00Z"),
                owner, 201);
        JsonNode openLog = getJson("/api/v1/jobs/" + jobId + "/profitability", owner, 200);
        assertThat(openLog.get("status").asText()).isEqualTo("INCOMPLETE");
        assertThat(openLog.get("reasons").toString()).contains("not been stopped");
        assertThat(openLog.path("directProfit").isMissingNode()).isTrue();
    }

    @Test
    void costingIsTenantScopedAndRoleGated() throws Exception {
        Session alpha = register("Alpha Costing", "owner@alphacost.example");
        Session beta = register("Beta Costing", "owner@betacost.example");
        UUID customer = createCustomer(alpha, "Alpha Client");
        UUID location = locationOf(alpha, customer);
        JsonNode job = postJson("/api/v1/jobs", Map.of(
                "customerId", customer, "locationId", location, "title", "Alpha visit",
                "scheduledStart", "2027-06-01T09:00:00Z",
                "scheduledEnd", "2027-06-01T11:00:00Z",
                "technicianIds", List.of(alpha.userId())), alpha, 201);
        UUID jobId = UUID.fromString(job.get("id").asText());

        getJson("/api/v1/jobs/" + jobId + "/costing", beta, 404);
        postJson("/api/v1/jobs/" + jobId + "/expenses", Map.of(
                "description", "Beta intrusion", "amount", 10, "currency", "USD",
                "expenseDate", "2027-06-01", "category", "OTHER"), beta, 404);
        assertThat(getJson("/api/v1/vendors", beta, 200).size()).isZero();

        // A technician may log their own time but not book money.
        postJson("/api/v1/team", Map.of(
                "fullName", "Terry Technician", "email", "terry@alphacost.example",
                "password", "CorrectHorse9Battery", "role", "TECHNICIAN"), alpha, 201);
        Session tech = login("terry@alphacost.example", "CorrectHorse9Battery");

        getJson("/api/v1/jobs/" + jobId + "/costing", tech, 200);
        postJson("/api/v1/jobs/" + jobId + "/work-logs", Map.of("startAt", "2027-06-01T09:00:00Z"),
                tech, 201);
        postJson("/api/v1/jobs/" + jobId + "/expenses", Map.of(
                "description", "Unauthorised", "amount", 10, "currency", "USD",
                "expenseDate", "2027-06-01", "category", "OTHER"), tech, 403);
    }

    private void assertDecimal(JsonNode node, String expected) {
        assertThat(node.decimalValue()).isEqualByComparingTo(new BigDecimal(expected));
    }
}
