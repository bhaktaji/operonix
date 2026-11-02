package com.operonix.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Boots one embedded PostgreSQL instance for the whole test JVM and runs Flyway against it,
 * so integration tests exercise the real schema instead of an H2 approximation.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    private static final EmbeddedPostgres POSTGRES;

    static {
        try {
            POSTGRES = EmbeddedPostgres.builder().setPort(0).start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    POSTGRES.close();
                } catch (IOException ignored) {
                    // best effort on JVM shutdown
                }
            }));
        } catch (IOException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", AbstractIntegrationTest::jdbcUrl);
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
    }

    private static String jdbcUrl() {
        try {
            return POSTGRES.getJdbcUrl("postgres", "postgres");
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to determine embedded PostgreSQL URL", ex);
        }
    }

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper mapper;

    // ------------------------------------------------------------------ fixtures

    public record Session(String accessToken, String refreshToken, UUID userId, UUID tenantId) {
    }

    protected Session register(String orgName, String email) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("orgName", orgName);
        body.put("fullName", "Test Owner");
        body.put("email", email);
        body.put("password", "CorrectHorse9Battery");
        return registerWith(body);
    }

    /** Registers a workspace whose own clock is somewhere specific, e.g. {@code Asia/Kolkata}. */
    protected Session register(String orgName, String email, String timezone) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("orgName", orgName);
        body.put("fullName", "Test Owner");
        body.put("email", email);
        body.put("password", "CorrectHorse9Battery");
        body.put("countryCode", "IN");
        body.put("timezone", timezone);
        return registerWith(body);
    }

    private Session registerWith(Map<String, Object> body) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();
        return session(body(result));
    }

    protected Session session(JsonNode json) {
        return new Session(json.get("accessToken").asText(), json.get("refreshToken").asText(),
                UUID.fromString(json.get("user").get("id").asText()),
                UUID.fromString(json.get("tenant").get("id").asText()));
    }

    protected Session login(String email, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("email", email, "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        return session(body(result));
    }

    protected JsonNode postJson(String path, Object body, Session session, int expectedStatus) throws Exception {
        var request = post(path).contentType(MediaType.APPLICATION_JSON);
        if (session != null) {
            request.header("Authorization", "Bearer " + session.accessToken());
        }
        if (body != null) {
            request.content(mapper.writeValueAsString(body));
        }
        MvcResult result = mvc.perform(request).andExpect(status().is(expectedStatus)).andReturn();
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8).isBlank()
                ? mapper.createObjectNode() : body(result);
    }

    protected JsonNode putJson(String path, Object body, Session session, int expectedStatus) throws Exception {
        MvcResult result = mvc.perform(put(path)
                        .header("Authorization", "Bearer " + session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().is(expectedStatus))
                .andReturn();
        return body(result);
    }

    protected JsonNode getJson(String path, Session session, int expectedStatus) throws Exception {
        MvcResult result = mvc.perform(get(path)
                        .header("Authorization", "Bearer " + session.accessToken()))
                .andExpect(status().is(expectedStatus))
                .andReturn();
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8).isBlank()
                ? mapper.createObjectNode() : body(result);
    }

    protected JsonNode body(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    protected UUID createCustomer(Session session, String name) throws Exception {
        Map<String, Object> body = Map.of(
                "name", name,
                "email", "billing@" + name.toLowerCase().replace(" ", "") + ".example",
                "primaryLocation", Map.of(
                        "label", "Main site",
                        "line1", "18 Harbor St",
                        "city", "Boston",
                        "region", "MA",
                        "postalCode", "02110",
                        "country", "US",
                        "isPrimary", true));
        return UUID.fromString(postJson("/api/v1/customers", body, session, 201).get("id").asText());
    }

    protected UUID locationOf(Session session, UUID customerId) throws Exception {
        return UUID.fromString(getJson("/api/v1/customers/" + customerId + "/locations", session, 200)
                .get(0).get("id").asText());
    }

    protected UUID createService(Session session, String name, String price, String taxRate) throws Exception {
        Map<String, Object> body = Map.of(
                "name", name,
                "unit", "VISIT",
                "basePrice", price,
                "durationMinutes", 120,
                "taxRate", taxRate,
                "active", true);
        return UUID.fromString(postJson("/api/v1/services", body, session, 201).get("id").asText());
    }
}
