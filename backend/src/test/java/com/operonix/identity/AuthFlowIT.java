package com.operonix.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.operonix.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthFlowIT extends AbstractIntegrationTest {

    @Test
    void registrationCreatesTenantAndOwnerAndReturnsSession() throws Exception {
        Session session = register("Sparkle Clean", "owner@sparkle.example");

        JsonNode me = getJson("/api/v1/auth/me", session, 200);
        assertThat(me.get("user").get("email").asText()).isEqualTo("owner@sparkle.example");
        assertThat(me.get("user").get("role").asText()).isEqualTo("OWNER");
        assertThat(UUID.fromString(me.get("user").get("tenantId").asText())).isEqualTo(session.tenantId());
        assertThat(me.get("tenant").get("currency").asText()).isEqualTo("USD");
    }

    @Test
    void loginRejectsWrongPasswordWithoutRevealingWhy() throws Exception {
        register("Brightline", "owner@brightline.example");

        JsonNode problem = postJson("/api/v1/auth/login",
                Map.of("email", "owner@brightline.example", "password", "WrongPassword123"), null, 401);
        assertThat(problem.get("code").asText()).isEqualTo("INVALID_CREDENTIALS");

        JsonNode unknown = postJson("/api/v1/auth/login",
                Map.of("email", "nobody@brightline.example", "password", "WrongPassword123"), null, 401);
        assertThat(unknown.get("code").asText()).isEqualTo("INVALID_CREDENTIALS");
        assertThat(unknown.get("detail").asText()).isEqualTo(problem.get("detail").asText());
    }

    @Test
    void refreshRotatesTokensAndReuseRevokesEverySession() throws Exception {
        Session session = register("Rotation Co", "owner@rotation.example");

        JsonNode refreshed = postJson("/api/v1/auth/refresh",
                Map.of("refreshToken", session.refreshToken()), null, 200);
        String rotated = refreshed.get("refreshToken").asText();
        assertThat(rotated).isNotEqualTo(session.refreshToken());

        JsonNode reuse = postJson("/api/v1/auth/refresh",
                Map.of("refreshToken", session.refreshToken()), null, 401);
        assertThat(reuse.get("code").asText()).isEqualTo("REFRESH_TOKEN_REUSED");

        // Reuse detection revokes the whole family, including the freshly rotated token.
        JsonNode afterReuse = postJson("/api/v1/auth/refresh",
                Map.of("refreshToken", rotated), null, 401);
        assertThat(afterReuse.get("code").asText()).isIn("REFRESH_TOKEN_REUSED", "REFRESH_TOKEN_INVALID");
    }

    @Test
    void tenantsCannotSeeEachOthersCustomers() throws Exception {
        Session alpha = register("Alpha Cleaning", "owner@alpha.example");
        Session beta = register("Beta Cleaning", "owner@beta.example");

        UUID customer = createCustomer(alpha, "Alpha Client");

        getJson("/api/v1/customers/" + customer, alpha, 200);
        getJson("/api/v1/customers/" + customer, beta, 404);

        JsonNode betaCustomers = getJson("/api/v1/customers", beta, 200);
        assertThat(betaCustomers.get("totalElements").asInt()).isZero();
    }

    @Test
    void technicianCannotReachFinancialEndpoints() throws Exception {
        Session owner = register("Field Team Co", "owner@fieldteam.example");
        postJson("/api/v1/team", Map.of(
                "fullName", "Sam Ortiz",
                "email", "sam@fieldteam.example",
                "password", "Fieldwork9Clean",
                "role", "TECHNICIAN"), owner, 201);

        Session technician = login("sam@fieldteam.example", "Fieldwork9Clean");
        getJson("/api/v1/quotes", technician, 403);
        getJson("/api/v1/invoices", technician, 403);
        getJson("/api/v1/jobs", technician, 200);

        JsonNode dashboard = getJson("/api/v1/reports/dashboard", technician, 200);
        assertThat(dashboard.get("revenueThisMonth")).isNull();
        assertThat(dashboard.get("jobsToday")).isNotNull();
    }

    @Test
    void unauthenticatedRequestsGetProblemJson() throws Exception {
        var response = mvc.perform(get("/api/v1/customers"))
                .andExpect(status().isUnauthorized())
                .andReturn();
        JsonNode problem = body(response);
        assertThat(problem.get("code").asText()).isEqualTo("UNAUTHENTICATED");
        assertThat(problem.get("requestId").asText()).isNotBlank();
    }

    @Test
    void logoutRevokesRefreshToken() throws Exception {
        Session session = register("Logout Co", "owner@logout.example");

        var response = mvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("refreshToken", session.refreshToken()))))
                .andExpect(status().isNoContent())
                .andReturn();
        assertThat(response.getResponse().getStatus()).isEqualTo(204);

        postJson("/api/v1/auth/refresh", Map.of("refreshToken", session.refreshToken()), null, 401);
    }

    @Test
    void teamAndWorkspaceAdministrationAreOfficeOnly() throws Exception {
        Session owner = register("Office Co", "owner@officeco.example");
        postJson("/api/v1/team", Map.of(
                "fullName", "Casey Manager",
                "email", "casey@officeco.example",
                "password", "Fieldwork9Clean",
                "role", "MANAGER"), owner, 201);
        postJson("/api/v1/team", Map.of(
                "fullName", "Robin Tech",
                "email", "robin@officeco.example",
                "password", "Fieldwork9Clean",
                "role", "TECHNICIAN"), owner, 201);

        Session manager = login("casey@officeco.example", "Fieldwork9Clean");
        Session technician = login("robin@officeco.example", "Fieldwork9Clean");

        // Managers schedule crews but do not administer people or the workspace.
        getJson("/api/v1/team/assignable", manager, 200);
        getJson("/api/v1/team", manager, 403);
        getJson("/api/v1/workspace", manager, 403);

        getJson("/api/v1/team", technician, 403);
        getJson("/api/v1/team/assignable", technician, 403);
        getJson("/api/v1/workspace", technician, 403);

        getJson("/api/v1/team", owner, 200);
        getJson("/api/v1/workspace", owner, 200);
    }

    @Test
    void createResponsesCarryTimestamps() throws Exception {
        Session session = register("Stamp Co", "owner@stampco.example");

        JsonNode customer = postJson("/api/v1/customers", Map.of("name", "Stamped Client"), session, 201);
        assertThat(customer.get("createdAt").asText()).isNotBlank();
        assertThat(customer.get("updatedAt").asText()).isNotBlank();

        JsonNode service = postJson("/api/v1/services", Map.of(
                "name", "Stamped service",
                "unit", "VISIT",
                "basePrice", "10.00",
                "durationMinutes", 30,
                "taxRate", "0",
                "active", true), session, 201);
        assertThat(service.get("createdAt").asText()).isNotBlank();
        assertThat(service.get("updatedAt").asText()).isNotBlank();

        JsonNode teamMember = postJson("/api/v1/team", Map.of(
                "fullName", "Stamped Tech",
                "email", "stamp@stampco.example",
                "password", "Fieldwork9Clean",
                "role", "TECHNICIAN"), session, 201);
        assertThat(teamMember.get("createdAt").asText()).isNotBlank();
    }

    @Test
    void auditLogFiltersOnActionAndDateRange() throws Exception {
        Session session = register("Audit Co", "owner@auditco.example");
        createCustomer(session, "Pier Cafe");

        // Unfiltered: PostgreSQL rejects untyped null bind parameters, so this guards the
        // optional-filter path as well as the happy path.
        JsonNode all = getJson("/api/v1/audit-events", session, 200);
        assertThat(all.get("totalElements").asInt()).isGreaterThanOrEqualTo(2);

        JsonNode created = getJson("/api/v1/audit-events?action=CUSTOMER_CREATED", session, 200);
        assertThat(created.get("totalElements").asInt()).isEqualTo(1);
        assertThat(created.get("content").get(0).get("action").asText()).isEqualTo("CUSTOMER_CREATED");

        JsonNode today = getJson("/api/v1/audit-events?from=2000-01-01T00:00:00Z&to=2999-01-01T00:00:00Z",
                session, 200);
        assertThat(today.get("totalElements").asInt()).isGreaterThanOrEqualTo(2);

        getJson("/api/v1/audit-events?from=2999-01-01T00:00:00Z&to=2000-01-01T00:00:00Z", session, 400);
    }

    @Test
    void auditLogIsIsolatedAndRestrictedToOfficeRoles() throws Exception {
        Session alpha = register("Audit Alpha", "owner@auditalpha.example");
        Session beta = register("Audit Beta", "owner@auditbeta.example");
        createCustomer(alpha, "Alpha Only Client");

        JsonNode betaEvents = getJson("/api/v1/audit-events?action=CUSTOMER_CREATED", beta, 200);
        assertThat(betaEvents.get("totalElements").asInt()).isZero();

        postJson("/api/v1/team", Map.of(
                "fullName", "Rae Tech",
                "email", "rae@auditalpha.example",
                "password", "Fieldwork9Clean",
                "role", "TECHNICIAN"), alpha, 201);
        Session technician = login("rae@auditalpha.example", "Fieldwork9Clean");
        getJson("/api/v1/audit-events", technician, 403);
    }
}
