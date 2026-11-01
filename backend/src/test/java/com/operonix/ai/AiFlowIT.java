package com.operonix.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.operonix.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2b: the assistant must answer with server-calculated figures, respect quotas and degrade
 * gracefully. The test profile has no provider key, which is exactly the degraded path every
 * deployment starts in.
 */
class AiFlowIT extends AbstractIntegrationTest {

    @Test
    void assistantAnswersFromCalculatedFiguresAndRespectsQuotas() throws Exception {
        Session owner = register("Lumen Facilities", "owner@lumen.example");
        UUID customer = createCustomer(owner, "Lumen Client");
        UUID location = locationOf(owner, customer);
        UUID service = createService(owner, "Scheduled maintenance", "200.00", "0");

        JsonNode quote = postJson("/api/v1/quotes", Map.of(
                "customerId", customer, "locationId", location,
                "lines", List.of(Map.of("serviceItemId", service, "quantity", 1))), owner, 201);
        UUID quoteId = UUID.fromString(quote.get("id").asText());
        postJson("/api/v1/quotes/" + quoteId + "/send", null, owner, 200);
        postJson("/api/v1/quotes/" + quoteId + "/accept", null, owner, 200);

        LocalDate today = LocalDate.now();
        JsonNode job = postJson("/api/v1/quotes/" + quoteId + "/convert", Map.of(
                "scheduledStart", today + "T08:00:00Z",
                "scheduledEnd", today + "T10:00:00Z",
                "technicianIds", List.of(owner.userId())), owner, 201);
        UUID jobId = UUID.fromString(job.get("id").asText());
        JsonNode invoice = postJson("/api/v1/jobs/" + jobId + "/invoice", Map.of(), owner, 201);
        UUID invoiceId = UUID.fromString(invoice.get("id").asText());
        postJson("/api/v1/invoices/" + invoiceId + "/send", null, owner, 200);
        postJson("/api/v1/invoices/" + invoiceId + "/payments",
                Map.of("amount", 200, "method", "CARD"), owner, 201);

        // No key in the test profile: the feature is honest about it rather than failing.
        JsonNode status = getJson("/api/v1/ai/status", owner, 200);
        assertThat(status.get("enabled").asBoolean()).isTrue();
        assertThat(status.get("providerConfigured").asBoolean()).isFalse();
        assertThat(status.get("note").asText()).contains("built-in explanations");

        JsonNode answer = postJson("/api/v1/ai/ask",
                Map.of("question", "What is our revenue this month?"), owner, 200);
        assertThat(answer.get("answered").asBoolean()).isTrue();
        assertThat(answer.get("status").asText()).isEqualTo("DEGRADED");
        assertThat(answer.get("narrativeFromModel").asBoolean()).isFalse();
        assertThat(answer.get("narrative").asText()).contains("200");
        assertThat(answer.get("intent").asText()).contains("revenue");

        JsonNode readings = answer.get("metrics");
        assertThat(readings.toString()).contains("\"revenue\"");
        JsonNode revenue = null;
        for (JsonNode reading : readings) {
            if ("revenue".equals(reading.get("key").asText())) {
                revenue = reading;
            }
        }
        assertThat(revenue).isNotNull();
        assertThat(revenue.get("value").decimalValue()).isEqualByComparingTo("200.00");

        // The daily limit is enforced before any provider call.
        putJson("/api/v1/ai/settings", Map.of("dailyQueryLimit", 1), owner, 200);
        JsonNode blocked = postJson("/api/v1/ai/ask",
                Map.of("question", "How much revenue this month?"), owner, 200);
        assertThat(blocked.get("status").asText()).isEqualTo("QUOTA_EXCEEDED");
        assertThat(blocked.get("answered").asBoolean()).isFalse();

        JsonNode usage = getJson("/api/v1/ai/usage", owner, 200);
        assertThat(usage.get("usedToday").asInt()).isEqualTo(1);
        assertThat(usage.get("dailyQueryLimit").asInt()).isEqualTo(1);
        assertThat(usage.get("recent").size()).isGreaterThan(0);

        // Quotation assistance never writes a quote.
        JsonNode assists = postJson("/api/v1/ai/quote-assist", Map.of(), owner, 200);
        assertThat(assists.get("suggestions").size()).isEqualTo(1);
        JsonNode suggestion = assists.get("suggestions").get(0);
        assertThat(suggestion.get("name").asText()).isEqualTo("Scheduled maintenance");
        assertThat(suggestion.get("suggestedPrice").decimalValue())
                .isGreaterThanOrEqualTo(suggestion.get("cost").decimalValue());
        assertThat(assists.get("disclaimer").asText()).contains("No quote was created");
        assertThat(getJson("/api/v1/quotes", owner, 200).get("totalElements").asLong()).isEqualTo(1);

        JsonNode insights = getJson("/api/v1/ai/insights", owner, 200);
        assertThat(insights.get("insights").size()).isGreaterThan(0);

        // Turning the assistant off is explicit, and nothing else breaks.
        putJson("/api/v1/ai/settings", Map.of("enabled", false), owner, 200);
        JsonNode disabled = postJson("/api/v1/ai/ask",
                Map.of("question", "What is our revenue this month?"), owner, 200);
        assertThat(disabled.get("status").asText()).isEqualTo("DISABLED");
        getJson("/api/v1/analytics/overview", owner, 200);
    }

    @Test
    void usageIsTenantScopedAndFieldRolesCannotAsk() throws Exception {
        Session alpha = register("Alpha Assistant", "owner@alphaassist.example");
        Session beta = register("Beta Assistant", "owner@betaassist.example");

        getJson("/api/v1/ai/status", beta, 200);
        JsonNode betaUsage = getJson("/api/v1/ai/usage", beta, 200);
        assertThat(betaUsage.get("usedToday").asInt()).isZero();

        postJson("/api/v1/team", Map.of(
                "fullName", "Tara Field", "email", "tara@alphaassist.example",
                "password", "CorrectHorse9Battery", "role", "TECHNICIAN"), alpha, 201);
        Session tech = login("tara@alphaassist.example", "CorrectHorse9Battery");

        // Status is safe to expose; asking and settings are not.
        getJson("/api/v1/ai/status", tech, 200);
        postJson("/api/v1/ai/ask", Map.of("question", "Show revenue"), tech, 403);
        putJson("/api/v1/ai/settings", Map.of("enabled", false), tech, 403);
    }
}
