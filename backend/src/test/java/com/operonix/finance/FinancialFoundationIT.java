package com.operonix.finance;

import com.fasterxml.jackson.databind.JsonNode;
import com.operonix.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P1a exit gate (IMPLEMENTATION_ROADMAP.md §3): the numbers are legally plausible and every historical
 * document keeps the currency, tax and FX values it was issued with.
 */
class FinancialFoundationIT extends AbstractIntegrationTest {

    // ------------------------------------------------------------------- fixtures

    private Session registerIn(String orgName, String email, String country) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("orgName", orgName);
        body.put("fullName", "Test Owner");
        body.put("email", email);
        body.put("password", "CorrectHorse9Battery");
        body.put("countryCode", country);
        var result = mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode json = body(result);
        return new Session(json.get("accessToken").asText(), json.get("refreshToken").asText(),
                UUID.fromString(json.get("user").get("id").asText()),
                UUID.fromString(json.get("tenant").get("id").asText()));
    }

    private JsonNode defaultProfile(Session session) throws Exception {
        return getJson("/api/v1/tax/profiles", session, 200).get(0);
    }

    private UUID createServiceWithProfile(Session session, String name, String price, String taxRate,
                                          UUID profileId) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("unit", "VISIT");
        body.put("basePrice", price);
        body.put("durationMinutes", 60);
        body.put("taxRate", taxRate);
        body.put("taxProfileId", profileId == null ? null : profileId.toString());
        body.put("active", true);
        return UUID.fromString(postJson("/api/v1/services", body, session, 201).get("id").asText());
    }

    private UUID createInvoice(Session session, UUID customerId, UUID serviceId, String currency)
            throws Exception {
        Map<String, Object> line = new HashMap<>();
        line.put("serviceItemId", serviceId.toString());
        line.put("quantity", 1);
        Map<String, Object> body = new HashMap<>();
        body.put("customerId", customerId.toString());
        body.put("currency", currency);
        body.put("lines", List.of(line));
        return UUID.fromString(postJson("/api/v1/invoices", body, session, 201).get("id").asText());
    }

    private static BigDecimal amount(JsonNode node, String field) {
        return new BigDecimal(node.get(field).asText());
    }

    private JsonNode createJson(String path, Map<String, Object> body, Session session, int status)
            throws Exception {
        return postJson(path, body, session, status);
    }

    private String definitionId(Session session, String code) throws Exception {
        for (JsonNode definition : getJson("/api/v1/tax/definitions", session, 200)) {
            if (definition.get("code").asText().equals(code)) {
                return definition.get("id").asText();
            }
        }
        throw new IllegalStateException("tax definition not seeded: " + code);
    }

    // ---------------------------------------------------------------------- tests

    @Test
    void newWorkspaceIsSeededWithEntityProfileSettingsAndLocation() throws Exception {
        Session session = registerIn("Seeded Workspace", "seed@example.com", "IN");

        JsonNode entities = getJson("/api/v1/legal-entities", session, 200);
        assertThat(entities).hasSize(1);
        assertThat(entities.get(0).get("defaultEntity").asBoolean()).isTrue();
        assertThat(entities.get(0).get("baseCurrency").asText()).isEqualTo("INR");
        assertThat(entities.get(0).get("taxNumberLabel").asText()).isEqualTo("GSTIN");

        JsonNode profiles = getJson("/api/v1/tax/profiles", session, 200);
        assertThat(profiles).hasSize(1);
        assertThat(profiles.get(0).get("taxDefinitionCode").asText()).isEqualTo("IN-GST-18");
        assertThat(profiles.get(0).get("components")).hasSize(2);

        JsonNode settings = getJson("/api/v1/workspace/financial-settings", session, 200);
        assertThat(settings.get("currencyPrecision").asInt()).isEqualTo(2);
        assertThat(settings.get("baseCurrency").asText()).isEqualTo("INR");

        JsonNode locations = getJson("/api/v1/business-locations", session, 200);
        assertThat(locations).hasSize(1);
        assertThat(locations.get(0).get("defaultLocation").asBoolean()).isTrue();
    }

    @Test
    void inclusiveTaxKeepsTheEnteredGrossAndPrintsTheVatComponent() throws Exception {
        Session session = registerIn("UK Inclusive Co", "vat@example.com", "GB");
        UUID profileId = UUID.fromString(defaultProfile(session).get("id").asText());
        UUID customerId = createCustomer(session, "Inclusive Customer");
        // 120.00 is the price the customer sees: VAT 20% is already inside it.
        UUID serviceId = createServiceWithProfile(session, "Callout", "120.00", "0.20", profileId);

        UUID invoiceId = createInvoice(session, customerId, serviceId, "GBP");
        JsonNode invoice = getJson("/api/v1/invoices/" + invoiceId, session, 200);

        assertThat(amount(invoice, "total")).isEqualByComparingTo("120.00");
        assertThat(amount(invoice, "subtotal")).isEqualByComparingTo("100.00");
        assertThat(amount(invoice, "taxAmount")).isEqualByComparingTo("20.00");
        assertThat(invoice.get("taxMode").asText()).isEqualTo("INCLUSIVE");
        assertThat(invoice.get("taxComponents")).hasSize(1);
        assertThat(invoice.get("taxComponents").get(0).get("code").asText()).isEqualTo("VAT");
        assertThat(amount(invoice.get("taxComponents").get(0), "amount")).isEqualByComparingTo("20.00");
    }

    @Test
    void zeroDecimalCurrencyRoundsEveryTaxAmountToWholeYen() throws Exception {
        Session session = registerIn("Yen Workspace", "yen@example.com", "JP");
        // Make the yen the billing currency of the default entity, then bill with an exclusive 18% rate.
        JsonNode entity = getJson("/api/v1/legal-entities", session, 200).get(0);
        Map<String, Object> entityUpdate = new HashMap<>();
        entityUpdate.put("name", entity.get("name").asText());
        entityUpdate.put("countryCode", "JP");
        entityUpdate.put("baseCurrency", "JPY");
        entityUpdate.put("taxMode", "EXCLUSIVE");
        entityUpdate.put("makeDefault", true);
        putJson("/api/v1/legal-entities/" + entity.get("id").asText(), entityUpdate, session, 200);

        // Point the workspace's profile at the exclusive 18% GST so the rounding is visible.
        JsonNode profile = defaultProfile(session);
        UUID profileId = UUID.fromString(profile.get("id").asText());
        String gstId = definitionId(session, "IN-GST-18");
        Map<String, Object> profileUpdate = new HashMap<>();
        profileUpdate.put("name", profile.get("name").asText());
        profileUpdate.put("taxDefinitionId", gstId);
        putJson("/api/v1/tax/profiles/" + profileId, profileUpdate, session, 200);

        UUID customerId = createCustomer(session, "Yen Customer");
        UUID serviceId = createServiceWithProfile(session, "Inspection", "1234", "0.18", profileId);

        UUID invoiceId = createInvoice(session, customerId, serviceId, "JPY");
        JsonNode invoice = getJson("/api/v1/invoices/" + invoiceId, session, 200);

        // 1234 * 18% = 222.12 -> 222 at zero decimal places, not 222.12.
        assertThat(amount(invoice, "taxAmount")).isEqualByComparingTo("222");
        assertThat(amount(invoice, "subtotal")).isEqualByComparingTo("1234");
        assertThat(amount(invoice, "total")).isEqualByComparingTo("1456");
    }

    @Test
    void fxRateIsSnapshottedAndNeverRepriced() throws Exception {
        Session session = registerIn("FX Workspace", "fx@example.com", "US");
        createJson("/api/v1/exchange-rates", Map.of("base", "INR", "quote", "USD",
                "rate", "0.012", "asOf", LocalDate.now().toString()), session, 201);

        UUID customerId = createCustomer(session, "FX Customer");
        // No profile: this line uses its explicit zero rate, so the FX maths is isolated.
        UUID serviceId = createServiceWithProfile(session, "Export job", "1000.00", "0.00", null);

        UUID invoiceId = createInvoice(session, customerId, serviceId, "INR");
        JsonNode invoice = getJson("/api/v1/invoices/" + invoiceId, session, 200);
        assertThat(invoice.get("currency").asText()).isEqualTo("INR");
        assertThat(invoice.get("baseCurrency").asText()).isEqualTo("USD");
        assertThat(amount(invoice, "total")).isEqualByComparingTo("1000.00");
        assertThat(amount(invoice, "baseTotal")).isEqualByComparingTo("12.00");

        // A newer rate must not touch a document that already exists.
        createJson("/api/v1/exchange-rates", Map.of("base", "INR", "quote", "USD",
                "rate", "0.020", "asOf", LocalDate.now().plusDays(1).toString()), session, 201);
        JsonNode again = getJson("/api/v1/invoices/" + invoiceId, session, 200);
        assertThat(amount(again, "baseTotal")).isEqualByComparingTo("12.00");
        assertThat(again.get("fxRate").decimalValue()).isEqualByComparingTo("0.012");
    }

    @Test
    void changingATaxProfileDoesNotRewriteIssuedInvoices() throws Exception {
        Session session = registerIn("Snapshot Co", "snapshot@example.com", "IN");
        JsonNode profile = defaultProfile(session);
        UUID profileId = UUID.fromString(profile.get("id").asText());
        UUID customerId = createCustomer(session, "Snapshot Customer");
        UUID serviceId = createServiceWithProfile(session, "Deep clean", "500.00", "0.18", profileId);

        UUID invoiceId = createInvoice(session, customerId, serviceId, "INR");
        JsonNode before = getJson("/api/v1/invoices/" + invoiceId, session, 200);
        assertThat(before.get("taxComponents")).hasSize(2);
        BigDecimal taxBefore = amount(before, "taxAmount");

        // Repoint the profile at a completely different jurisdiction.
        JsonNode definitions = getJson("/api/v1/tax/definitions", session, 200);
        String replacementId = null;
        for (JsonNode definition : definitions) {
            if (definition.get("code").asText().equals("US-CA-SALES-825")) {
                replacementId = definition.get("id").asText();
            }
        }
        assertThat(replacementId).isNotNull();
        Map<String, Object> update = new HashMap<>();
        update.put("name", profile.get("name").asText());
        update.put("taxDefinitionId", replacementId);
        putJson("/api/v1/tax/profiles/" + profileId, update, session, 200);

        JsonNode after = getJson("/api/v1/invoices/" + invoiceId, session, 200);
        assertThat(amount(after, "taxAmount")).isEqualByComparingTo(taxBefore);
        assertThat(after.get("taxComponents")).hasSize(2);
        assertThat(after.get("taxComponents").get(0).get("code").asText()).isEqualTo("CGST");
        assertThat(after.get("taxComponents").get(1).get("code").asText()).isEqualTo("SGST");
    }

    @Test
    void numberingRuleChangesOnlyAffectNewDocuments() throws Exception {
        Session session = registerIn("Numbering Co", "numbers@example.com", "IN");
        UUID profileId = UUID.fromString(defaultProfile(session).get("id").asText());
        UUID customerId = createCustomer(session, "Numbered Customer");
        UUID serviceId = createServiceWithProfile(session, "Service", "100.00", "0.18", profileId);
        int year = LocalDate.now().getYear();

        UUID firstId = createInvoice(session, customerId, serviceId, "INR");
        String firstNumber = getJson("/api/v1/invoices/" + firstId, session, 200).get("number").asText();
        assertThat(firstNumber).isEqualTo("INV-" + year + "-0001");

        putJson("/api/v1/number-rules/INVOICE", Map.of("prefix", "BILL", "includeYear", true,
                "padding", 5, "resetPolicy", "YEARLY"), session, 200);

        UUID secondId = createInvoice(session, customerId, serviceId, "INR");
        JsonNode second = getJson("/api/v1/invoices/" + secondId, session, 200);
        assertThat(second.get("number").asText()).isEqualTo("BILL-" + year + "-00002");

        JsonNode first = getJson("/api/v1/invoices/" + firstId, session, 200);
        assertThat(first.get("number").asText()).isEqualTo(firstNumber);
    }

    @Test
    void paymentsTrackTheBaseCurrencyAmountAtTheSnapshottedRate() throws Exception {
        Session session = registerIn("Payment FX Co", "payfx@example.com", "US");
        createJson("/api/v1/exchange-rates", Map.of("base", "INR", "quote", "USD",
                "rate", "0.012", "asOf", LocalDate.now().minusDays(1).toString()), session, 201);
        UUID customerId = createCustomer(session, "Paying Customer");
        UUID serviceId = createServiceWithProfile(session, "Retainer", "1000.00", "0.00", null);
        UUID invoiceId = createInvoice(session, customerId, serviceId, "INR");
        postJson("/api/v1/invoices/" + invoiceId + "/send", null, session, 200);

        postJson("/api/v1/invoices/" + invoiceId + "/payments",
                Map.of("amount", "500.00", "method", "BANK_TRANSFER"), session, 201);

        JsonNode invoice = getJson("/api/v1/invoices/" + invoiceId, session, 200);
        assertThat(amount(invoice, "amountPaid")).isEqualByComparingTo("500.00");
        assertThat(amount(invoice, "balance")).isEqualByComparingTo("500.00");
        assertThat(amount(invoice, "baseAmountPaid")).isEqualByComparingTo("6.00");
        assertThat(invoice.get("status").asText()).isEqualTo("PARTIALLY_PAID");
    }

    @Test
    void financialConfigurationIsTenantScoped() throws Exception {
        Session owner = registerIn("Isolated Co", "isolated@example.com", "IN");
        Session other = registerIn("Other Co", "other@example.com", "IN");

        String entityId = getJson("/api/v1/legal-entities", owner, 200).get(0).get("id").asText();
        getJson("/api/v1/legal-entities", other, 200);

        Map<String, Object> update = new HashMap<>();
        update.put("name", "Hijacked");
        update.put("countryCode", "IN");
        update.put("baseCurrency", "INR");
        putJson("/api/v1/legal-entities/" + entityId, update, other, 404);

        JsonNode profiles = getJson("/api/v1/tax/profiles", owner, 200);
        assertThat(profiles).hasSize(1);
        assertThat(getJson("/api/v1/tax/definitions", owner, 200).size()).isGreaterThan(5);

        JsonNode otherProfiles = getJson("/api/v1/tax/profiles", other, 200);
        assertThat(otherProfiles).hasSize(1);
        assertThat(otherProfiles.get(0).get("id").asText())
                .isNotEqualTo(profiles.get(0).get("id").asText());
    }
}
