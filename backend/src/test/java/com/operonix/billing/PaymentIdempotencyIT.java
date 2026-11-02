package com.operonix.billing;

import com.fasterxml.jackson.databind.JsonNode;
import com.operonix.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A flaky connection must not make a workspace take the same money twice. The client sends a key with
 * the attempt; replaying that key answers with the payment the attempt already created, while a new
 * attempt is a new key and records a second payment only when the balance allows it.
 */
class PaymentIdempotencyIT extends AbstractIntegrationTest {

    @Test
    void replayingTheSameKeyRecordsOnePayment() throws Exception {
        Session owner = register("Retry Safe Co", "owner@retrysafe.example");
        UUID invoiceId = invoicedJob(owner);

        Map<String, Object> payment = Map.of(
                "amount", 40, "method", "CASH", "idempotencyKey", "attempt-7f3c");

        JsonNode first = postJson("/api/v1/invoices/" + invoiceId + "/payments", payment, owner, 201);
        JsonNode replay = postJson("/api/v1/invoices/" + invoiceId + "/payments", payment, owner, 201);

        assertThat(replay.get("id").asText()).isEqualTo(first.get("id").asText());
        assertThat(replay.get("amount").decimalValue()).isEqualByComparingTo("40");

        JsonNode invoice = getJson("/api/v1/invoices/" + invoiceId, owner, 200);
        assertThat(invoice.get("payments")).hasSize(1);
        assertThat(invoice.get("amountPaid").decimalValue()).isEqualByComparingTo("40");
        assertThat(invoice.get("balance").decimalValue()).isEqualByComparingTo("60");
    }

    @Test
    void aDifferentKeyIsADifferentAttempt() throws Exception {
        Session owner = register("Second Attempt Co", "owner@secondattempt.example");
        UUID invoiceId = invoicedJob(owner);

        postJson("/api/v1/invoices/" + invoiceId + "/payments",
                Map.of("amount", 40, "method", "CASH", "idempotencyKey", "attempt-a"), owner, 201);
        postJson("/api/v1/invoices/" + invoiceId + "/payments",
                Map.of("amount", 20, "method", "CARD", "idempotencyKey", "attempt-b"), owner, 201);

        JsonNode invoice = getJson("/api/v1/invoices/" + invoiceId, owner, 200);
        assertThat(invoice.get("payments")).hasSize(2);
        assertThat(invoice.get("amountPaid").decimalValue()).isEqualByComparingTo("60");
    }

    @Test
    void aKeyFromAnotherWorkspaceCannotReturnItsPayment() throws Exception {
        Session alpha = register("Key Alpha Co", "owner@keyalpha.example");
        Session beta = register("Key Beta Co", "owner@keybeta.example");
        UUID alphaInvoice = invoicedJob(alpha);
        UUID betaInvoice = invoicedJob(beta);

        JsonNode alphaPayment = postJson("/api/v1/invoices/" + alphaInvoice + "/payments",
                Map.of("amount", 30, "method", "CASH", "idempotencyKey", "shared-key"), alpha, 201);
        JsonNode betaPayment = postJson("/api/v1/invoices/" + betaInvoice + "/payments",
                Map.of("amount", 30, "method", "CASH", "idempotencyKey", "shared-key"), beta, 201);

        assertThat(betaPayment.get("id").asText()).isNotEqualTo(alphaPayment.get("id").asText());
        assertThat(getJson("/api/v1/invoices/" + betaInvoice, beta, 200).get("payments")).hasSize(1);
    }

    // -------------------------------------------------------------------- fixture

    /** A delivered job with a 100.00 invoice, ready to take payments. */
    private UUID invoicedJob(Session owner) throws Exception {
        UUID customer = createCustomer(owner, "Retry Customer " + UUID.randomUUID().toString().substring(0, 8));
        UUID location = locationOf(owner, customer);
        UUID service = createService(owner, "Visit", "100.00", "0");
        LocalDate today = LocalDate.now();

        JsonNode job = postJson("/api/v1/jobs", Map.of(
                "customerId", customer, "locationId", location, "title", "Retry visit",
                "scheduledStart", today + "T09:00:00Z",
                "scheduledEnd", today + "T11:00:00Z",
                "technicianIds", List.of(owner.userId().toString())), owner, 201);
        UUID jobId = UUID.fromString(job.get("id").asText());
        postJson("/api/v1/jobs/" + jobId + "/transitions", Map.of("to", "IN_PROGRESS"), owner, 200);
        postJson("/api/v1/jobs/" + jobId + "/transitions", Map.of("to", "COMPLETED"), owner, 200);

        JsonNode invoice = postJson("/api/v1/jobs/" + jobId + "/invoice",
                Map.of("lines", List.of(Map.of("serviceItemId", service.toString(), "quantity", 1))), owner, 201);
        UUID invoiceId = UUID.fromString(invoice.get("id").asText());
        postJson("/api/v1/invoices/" + invoiceId + "/send", null, owner, 200);
        return invoiceId;
    }
}