package com.operonix.global;

import com.fasterxml.jackson.databind.JsonNode;
import com.operonix.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P0 foundation: platform reference data, country-driven onboarding defaults, multi-workspace
 * membership, tenant-scoped uniqueness and refresh-token rotation families.
 */
class GlobalFoundationIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "CorrectHorse9Battery";

    private JsonNode registerWith(String orgName, String email, Map<String, Object> extra) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("orgName", orgName);
        body.put("fullName", "Test Owner");
        body.put("email", email);
        body.put("password", PASSWORD);
        body.putAll(extra);
        return postJson("/api/v1/auth/register", body, null, 201);
    }

    @Test
    void referenceDataIsPublicAndCoversTheLaunchMarkets() throws Exception {
        JsonNode reference = body(mvc.perform(get("/api/v1/reference"))
                .andExpect(status().isOk())
                .andReturn());

        assertThat(reference.get("countries").isArray()).isTrue();
        assertThat(reference.get("currencies").isArray()).isTrue();
        assertThat(reference.get("industryTemplates").isArray()).isTrue();

        Map<String, JsonNode> countries = new HashMap<>();
        reference.get("countries").forEach(country ->
                countries.put(country.get("code").asText(), country));
        assertThat(countries).containsKeys("IN", "US", "GB", "JP");

        JsonNode japan = countries.get("JP");
        assertThat(japan.get("defaultCurrency").asText()).isEqualTo("JPY");
        assertThat(japan.get("taxLabel").asText()).isEqualTo("Invoice registration number");

        Map<String, JsonNode> currencies = new HashMap<>();
        reference.get("currencies").forEach(currency ->
                currencies.put(currency.get("code").asText(), currency));
        // Zero-decimal currency: the client needs this to stop rendering ".00".
        assertThat(currencies.get("JPY").get("minorUnits").asInt()).isZero();
        assertThat(currencies.get("INR").get("minorUnits").asInt()).isEqualTo(2);
    }

    @Test
    void registrationDerivesGlobalDefaultsFromTheCountry() throws Exception {
        JsonNode registered = registerWith("Mumbai Facilities", "owner@mumbai.example",
                Map.of("countryCode", "IN", "industryTemplate", "PEST_CONTROL"));

        JsonNode tenant = registered.get("tenant");
        assertThat(tenant.get("countryCode").asText()).isEqualTo("IN");
        assertThat(tenant.get("currency").asText()).isEqualTo("INR");
        assertThat(tenant.get("locale").asText()).isEqualTo("en-IN");
        assertThat(tenant.get("industryTemplate").asText()).isEqualTo("PEST_CONTROL");
    }

    @Test
    void registrationWithoutARegionKeepsTheOldContractWorking() throws Exception {
        JsonNode registered = registerWith("Legacy Co", "owner@legacy.example", Map.of());
        assertThat(registered.get("tenant").get("countryCode").asText()).isEqualTo("US");
        assertThat(registered.get("tenant").get("currency").asText()).isEqualTo("USD");
        assertThat(registered.get("tenant").get("industryTemplate").asText()).isEqualTo("GENERAL_SERVICE");
    }

    @Test
    void unsupportedCountryIsRejectedWithAFieldError() throws Exception {
        JsonNode problem = postJson("/api/v1/auth/register", Map.of(
                "orgName", "Nowhere Ltd",
                "fullName", "Test Owner",
                "email", "owner@nowhere.example",
                "password", PASSWORD,
                "countryCode", "ZZ"), null, 400);
        assertThat(problem.get("code").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(problem.get("errors").get(0).get("field").asText()).isEqualTo("countryCode");
    }

    @Test
    void theSameEmailCanBelongToTwoWorkspacesAndLoginAsksWhichOne() throws Exception {
        String email = "shared@multiworkspace.example";
        JsonNode first = registerWith("Northwind Services", email, Map.of("countryCode", "GB"));
        JsonNode second = registerWith("Southwind Rentals", email,
                Map.of("countryCode", "US", "industryTemplate", "EQUIPMENT_RENTAL"));

        assertThat(first.get("tenant").get("id").asText())
                .isNotEqualTo(second.get("tenant").get("id").asText());

        // Without a workspace the sign-in cannot guess, and it says so without leaking any data.
        JsonNode problem = postJson("/api/v1/auth/login",
                Map.of("email", email, "password", PASSWORD), null, 400);
        assertThat(problem.get("code").asText()).isEqualTo("WORKSPACE_REQUIRED");
        assertThat(problem.get("workspaces")).hasSize(2);

        String firstSlug = first.get("tenant").get("slug").asText();
        JsonNode chosen = postJson("/api/v1/auth/login",
                Map.of("email", email, "password", PASSWORD, "workspace", firstSlug), null, 200);
        assertThat(chosen.get("tenant").get("id").asText()).isEqualTo(first.get("tenant").get("id").asText());

        JsonNode wrongWorkspace = postJson("/api/v1/auth/login",
                Map.of("email", email, "password", PASSWORD, "workspace", "does-not-exist"), null, 404);
        assertThat(wrongWorkspace.get("code").asText()).isEqualTo("WORKSPACE_NOT_FOUND");
    }

    @Test
    void aWrongPasswordNeverDisclosesWorkspaces() throws Exception {
        String email = "quiet@multiworkspace.example";
        registerWith("Quiet One", email, Map.of("countryCode", "IN"));
        registerWith("Quiet Two", email, Map.of("countryCode", "IN"));

        JsonNode problem = postJson("/api/v1/auth/login",
                Map.of("email", email, "password", "WrongPassword123"), null, 401);
        assertThat(problem.get("code").asText()).isEqualTo("INVALID_CREDENTIALS");
        assertThat(problem.get("workspaces")).isNull();
    }

    @Test
    void workspaceSettingsPersistRegionalConfiguration() throws Exception {
        Session session = register("Config Co", "owner@config.example");

        JsonNode updated = putJson("/api/v1/workspace", Map.of(
                "name", "Config Co",
                "countryCode", "GB",
                "currency", "GBP",
                "timezone", "Europe/London",
                "locale", "en-GB",
                "industryTemplate", "PLUMBING_ELECTRICAL",
                "legalName", "Config Co Ltd",
                "taxNumber", "GB123456789"), session, 200);

        assertThat(updated.get("countryCode").asText()).isEqualTo("GB");
        assertThat(updated.get("currency").asText()).isEqualTo("GBP");
        assertThat(updated.get("industryTemplate").asText()).isEqualTo("PLUMBING_ELECTRICAL");

        JsonNode me = getJson("/api/v1/auth/me", session, 200);
        assertThat(me.get("tenant").get("locale").asText()).isEqualTo("en-GB");
        assertThat(me.get("tenant").get("industryTemplate").asText()).isEqualTo("PLUMBING_ELECTRICAL");
    }

    @Test
    void unsupportedCurrencyIsRejected() throws Exception {
        Session session = register("Currency Co", "owner@currency.example");
        JsonNode problem = putJson("/api/v1/workspace", Map.of(
                "name", "Currency Co",
                "countryCode", "US",
                "currency", "XYZ",
                "timezone", "UTC",
                "locale", "en-US",
                "industryTemplate", "GENERAL_SERVICE"), session, 400);
        assertThat(problem.get("code").asText()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void replayingOneSessionRevokesOnlyThatRotationFamily() throws Exception {
        Session session = register("Family Co", "owner@family.example");

        JsonNode secondLogin = postJson("/api/v1/auth/login",
                Map.of("email", "owner@family.example", "password", PASSWORD), null, 200);
        String otherSessionRefresh = secondLogin.get("refreshToken").asText();

        // Rotate the first session once, then replay its original token.
        JsonNode rotated = postJson("/api/v1/auth/refresh",
                Map.of("refreshToken", session.refreshToken()), null, 200);
        JsonNode reuse = postJson("/api/v1/auth/refresh",
                Map.of("refreshToken", session.refreshToken()), null, 401);
        assertThat(reuse.get("code").asText()).isEqualTo("REFRESH_TOKEN_REUSED");

        // The replayed chain is dead...
        postJson("/api/v1/auth/refresh",
                Map.of("refreshToken", rotated.get("refreshToken").asText()), null, 401);

        // ...but an independent sign-in on another device is untouched.
        JsonNode stillValid = postJson("/api/v1/auth/refresh",
                Map.of("refreshToken", otherSessionRefresh), null, 200);
        assertThat(stillValid.get("refreshToken").asText()).isNotBlank();
    }

    @Test
    void emailUniquenessIsScopedToTheWorkspace() throws Exception {
        Session alpha = register("Alpha Ops", "owner@alphaops.example");
        Session beta = register("Beta Ops", "owner@betaops.example");

        // Same address as another workspace's owner is fine.
        postJson("/api/v1/team", Map.of(
                "fullName", "Sam Cross",
                "email", "owner@alphaops.example",
                "password", "Fieldwork9Green",
                "role", "TECHNICIAN"), beta, 201);

        // Same address inside the same workspace is not.
        JsonNode conflict = postJson("/api/v1/team", Map.of(
                "fullName", "Sam Again",
                "email", "owner@alphaops.example",
                "password", "Fieldwork9Green",
                "role", "TECHNICIAN"), beta, 409);
        assertThat(conflict.get("code").asText()).isEqualTo("EMAIL_ALREADY_EXISTS");

        assertThat(alpha.tenantId()).isNotEqualTo(beta.tenantId());
    }
}