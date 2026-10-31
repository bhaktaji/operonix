package com.operonix.sales;

import com.fasterxml.jackson.databind.JsonNode;
import com.operonix.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SalesFlowIT extends AbstractIntegrationTest {

    @Test
    void quoteToCashFlowEnforcesTotalsSchedulingAndPaymentRules() throws Exception {
        Session owner = register("Operonix Cleaning", "owner@operonix.example");
        UUID customer = createCustomer(owner, "Brightline Offices");
        UUID location = locationOf(owner, customer);
        UUID service = createService(owner, "Deep clean", "100.00", "0.10");

        JsonNode quote = postJson("/api/v1/quotes", Map.of(
                "customerId", customer,
                "locationId", location,
                "discountAmount", 20,
                "lines", List.of(
                        Map.of("serviceItemId", service, "quantity", 2),
                        Map.of("description", "Window detailing", "quantity", 1,
                                "unitPrice", 50, "taxRate", 0.10))), owner, 201);

        UUID quoteId = UUID.fromString(quote.get("id").asText());
        assertThat(quote.get("number").asText()).startsWith("Q-");
        assertThat(quote.get("status").asText()).isEqualTo("DRAFT");
        assertThat(quote.get("lines").get(0).get("description").asText()).isEqualTo("Deep clean");
        assertDecimal(quote.get("subtotal"), "250.00");
        assertDecimal(quote.get("taxAmount"), "23.00");
        assertDecimal(quote.get("total"), "253.00");

        // Editing a sent quote is rejected.
        postJson("/api/v1/quotes/" + quoteId + "/send", null, owner, 200);
        putJson("/api/v1/quotes/" + quoteId, Map.of(
                "customerId", customer,
                "lines", List.of(Map.of("description", "x", "quantity", 1,
                        "unitPrice", 10, "taxRate", 0))), owner, 409);

        JsonNode accepted = postJson("/api/v1/quotes/" + quoteId + "/accept", null, owner, 200);
        assertThat(accepted.get("status").asText()).isEqualTo("ACCEPTED");
        postJson("/api/v1/quotes/" + quoteId + "/accept", null, owner, 409);

        JsonNode job = postJson("/api/v1/quotes/" + quoteId + "/convert", Map.of(
                "scheduledStart", "2027-03-01T15:00:00Z",
                "scheduledEnd", "2027-03-01T17:00:00Z",
                "technicianIds", List.of(owner.userId())), owner, 201);
        UUID jobId = UUID.fromString(job.get("id").asText());
        assertThat(job.get("number").asText()).startsWith("J-");
        assertThat(job.get("quoteId").asText()).isEqualTo(quoteId.toString());
        assertThat(getJson("/api/v1/quotes/" + quoteId, owner, 200).get("status").asText())
                .isEqualTo("CONVERTED");

        // A second job for the same technician in the same window is rejected with details.
        JsonNode conflict = postJson("/api/v1/jobs", Map.of(
                "customerId", customer,
                "locationId", location,
                "title", "Overlapping visit",
                "scheduledStart", "2027-03-01T16:00:00Z",
                "scheduledEnd", "2027-03-01T18:00:00Z",
                "technicianIds", List.of(owner.userId())), owner, 409);
        assertThat(conflict.get("code").asText()).isEqualTo("JOB_SCHEDULE_CONFLICT");
        assertThat(conflict.get("conflicts").get(0).get("number").asText()).isEqualTo(job.get("number").asText());

        long jobsBefore = getJson("/api/v1/jobs", owner, 200).get("totalElements").asLong();
        postJson("/api/v1/jobs/series", Map.of(
                "customerId", customer,
                "locationId", location,
                "scheduledStart", "2027-03-01T16:30:00Z",
                "scheduledEnd", "2027-03-01T17:30:00Z",
                "technicianIds", List.of(owner.userId()),
                "recurrence", "WEEKLY",
                "occurrences", 4), owner, 409);
        assertThat(getJson("/api/v1/jobs", owner, 200).get("totalElements").asLong()).isEqualTo(jobsBefore);

        JsonNode invoice = postJson("/api/v1/jobs/" + jobId + "/invoice", Map.of(), owner, 201);
        UUID invoiceId = UUID.fromString(invoice.get("id").asText());
        assertThat(invoice.get("number").asText()).startsWith("INV-");
        assertThat(invoice.get("lines").size()).isEqualTo(2);
        assertDecimal(invoice.get("total"), "253.00");
        assertDecimal(invoice.get("balance"), "253.00");

        // A job can only be invoiced once while an invoice is active.
        postJson("/api/v1/jobs/" + jobId + "/invoice", Map.of(), owner, 409);

        postJson("/api/v1/invoices/" + invoiceId + "/send", null, owner, 200);
        postJson("/api/v1/invoices/" + invoiceId + "/payments",
                Map.of("amount", 100, "method", "CASH"), owner, 201);

        JsonNode partial = getJson("/api/v1/invoices/" + invoiceId, owner, 200);
        assertThat(partial.get("status").asText()).isEqualTo("PARTIALLY_PAID");
        assertDecimal(partial.get("balance"), "153.00");
        assertThat(partial.get("overdue").asBoolean()).isFalse();

        postJson("/api/v1/invoices/" + invoiceId + "/payments",
                Map.of("amount", 200, "method", "CARD"), owner, 422);

        JsonNode finalPayment = postJson("/api/v1/invoices/" + invoiceId + "/payments",
                Map.of("amount", 153, "method", "BANK_TRANSFER"), owner, 201);
        JsonNode paid = getJson("/api/v1/invoices/" + invoiceId, owner, 200);
        assertThat(paid.get("status").asText()).isEqualTo("PAID");
        assertDecimal(paid.get("balance"), "0.00");
        assertThat(paid.get("payments").size()).isEqualTo(2);

        JsonNode dashboard = getJson("/api/v1/reports/dashboard", owner, 200);
        assertDecimal(dashboard.get("revenueThisMonth"), "253.00");
        assertDecimal(dashboard.get("outstandingTotal"), "0.00");
        assertThat(dashboard.get("activeCustomers").asLong()).isEqualTo(1);

        JsonNode reversed = deleteJson("/api/v1/invoices/" + invoiceId + "/payments/"
                + finalPayment.get("id").asText(), owner, 200);
        assertThat(reversed.get("status").asText()).isEqualTo("PARTIALLY_PAID");
        assertDecimal(reversed.get("balance"), "153.00");
    }

    @Test
    void quotesAndJobsAreInvisibleToOtherTenants() throws Exception {
        Session alpha = register("Alpha Ops", "owner@alphaops.example");
        Session beta = register("Beta Ops", "owner@betaops.example");
        UUID customer = createCustomer(alpha, "Alpha Client");
        UUID location = locationOf(alpha, customer);

        JsonNode quote = postJson("/api/v1/quotes", Map.of(
                "customerId", customer,
                "locationId", location,
                "lines", List.of(Map.of("description", "Standard clean", "quantity", 1,
                        "unitPrice", 80, "taxRate", 0))), alpha, 201);
        UUID quoteId = UUID.fromString(quote.get("id").asText());

        getJson("/api/v1/quotes/" + quoteId, beta, 404);
        postJson("/api/v1/quotes/" + quoteId + "/send", null, beta, 404);
        getJson("/api/v1/customers/" + customer + "/locations", beta, 404);
    }

    private void assertDecimal(JsonNode node, String expected) {
        assertThat(node.decimalValue()).isEqualByComparingTo(new BigDecimal(expected));
    }

    private JsonNode deleteJson(String path, Session session, int expectedStatus) throws Exception {
        var result = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete(path).header("Authorization", "Bearer " + session.accessToken()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().is(expectedStatus))
                .andReturn();
        return body(result);
    }
}
