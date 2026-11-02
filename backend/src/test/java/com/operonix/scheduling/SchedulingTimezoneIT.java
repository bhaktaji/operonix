package com.operonix.scheduling;

import com.fasterxml.jackson.databind.JsonNode;
import com.operonix.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * A wall-clock time belongs to where the work happens. These tests send the same local value a
 * Kolkata office would type for a Los Angeles address and prove it lands on the crew's morning:
 * the location zone decides, the workspace zone is the fallback, and the caller's device zone never
 * participates.
 *
 * <p>The workspace is registered once for the class: registration is rate limited per client address
 * (a security control worth keeping), and one shared workspace is all these cases need.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SchedulingTimezoneIT extends AbstractIntegrationTest {

    private Session owner;

    @BeforeAll
    void registerWorkspace() throws Exception {
        // Workspace clock: Kolkata. The office typing "09:00" for an LA site is booking 09:00 in LA.
        owner = register("Kolkata Field Ops", "owner@scheduling-zone.example", "Asia/Kolkata");
    }

    @Test
    void localWindowIsResolvedInTheLocationZoneNotTheWorkspaceOrDeviceZone() throws Exception {
        UUID customer = createCustomerAt("Pacific Plaza", "America/Los_Angeles");

        JsonNode job = book(customer, "2026-11-02T09:00", "2026-11-02T11:00", 201);

        // 2 Nov 2026 is after the US DST change, so Los Angeles is UTC-8.
        assertThat(job.get("scheduledStart").asText()).isEqualTo("2026-11-02T17:00:00Z");
        assertThat(job.get("scheduledEnd").asText()).isEqualTo("2026-11-02T19:00:00Z");
    }

    @Test
    void nonexistentLocalTimeInTheSpringForwardGapMovesForward() throws Exception {
        UUID customer = createCustomerAt("Gap Test Customer", "America/Los_Angeles");

        // 02:30 does not exist on 8 Mar 2026 in Los Angeles; the clock jumps 02:00 -> 03:00 (PDT).
        JsonNode job = book(customer, "2026-03-08T02:30", "2026-03-08T04:30", 201);

        assertThat(job.get("scheduledStart").asText()).isEqualTo("2026-03-08T10:30:00Z");
        assertThat(job.get("scheduledEnd").asText()).isEqualTo("2026-03-08T11:30:00Z");
    }

    @Test
    void ambiguousLocalTimeInTheFallBackHourTakesTheFirstOccurrence() throws Exception {
        UUID customer = createCustomerAt("Overlap Test Customer", "America/Los_Angeles");

        // 01:30 happens twice on 1 Nov 2026; the earlier offset (PDT, UTC-7) wins, as documented.
        JsonNode job = book(customer, "2026-11-01T01:30", "2026-11-01T03:30", 201);

        assertThat(job.get("scheduledStart").asText()).isEqualTo("2026-11-01T08:30:00Z");
        assertThat(job.get("scheduledEnd").asText()).isEqualTo("2026-11-01T11:30:00Z");
    }

    @Test
    void locationWithoutATimezoneFallsBackToTheWorkspace() throws Exception {
        UUID customer = createCustomer(owner, "No Zone Customer");

        JsonNode job = book(customer, "2026-12-01T09:00", "2026-12-01T10:00", 201);

        // 09:00 IST is 03:30 UTC; no device zone is involved anywhere in the request.
        assertThat(job.get("scheduledStart").asText()).isEqualTo("2026-12-01T03:30:00Z");
    }

    @Test
    void instantsRemainAcceptedForBackwardCompatibility() throws Exception {
        UUID customer = createCustomer(owner, "Instant Customer");

        Map<String, Object> body = baseWindow(customer);
        body.put("scheduledStart", "2026-12-02T09:00:00Z");
        body.put("scheduledEnd", "2026-12-02T10:00:00Z");
        JsonNode job = postJson("/api/v1/jobs", body, owner, 201);

        assertThat(job.get("scheduledStart").asText()).isEqualTo("2026-12-02T09:00:00Z");
    }

    @Test
    void sendingBothAWindowValueAndAnInstantIsRejected() throws Exception {
        UUID customer = createCustomer(owner, "Both Customer");

        Map<String, Object> body = baseWindow(customer);
        body.put("scheduledStart", "2026-12-03T09:00:00Z");
        body.put("startLocal", "2026-12-03T09:00");
        postJson("/api/v1/jobs", body, owner, 400);
    }

    @Test
    void aDeviceOffsetInsideTheLocalValueIsRejectedSoTheZoneStaysAuthoritative() throws Exception {
        UUID customer = createCustomer(owner, "Offset Customer");

        Map<String, Object> body = baseWindow(customer);
        body.put("startLocal", "2026-12-04T09:00:00+05:30");
        postJson("/api/v1/jobs", body, owner, 400);
    }

    @Test
    void anUnknownLocationTimeZoneIsRejectedWhenTheLocationIsSaved() throws Exception {
        UUID customer = createCustomer(owner, "Bad Zone Customer");

        postJson("/api/v1/customers/" + customer + "/locations",
                location("Nowhere", "Mars/Olympus"), owner, 400);

        JsonNode updated = postJson("/api/v1/customers/" + customer + "/locations",
                location("Chicago depot", "America/Chicago"), owner, 201);
        assertThat(updated.get("timezone").asText()).isEqualTo("America/Chicago");
    }

    // -------------------------------------------------------------------- fixtures

    private UUID createCustomerAt(String name, String timezone) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("email", "billing@" + name.toLowerCase().replaceAll("[^a-z]", "") + ".example");
        body.put("primaryLocation", location("Main site", timezone));
        JsonNode created = postJson("/api/v1/customers", body, owner, 201);
        return UUID.fromString(created.get("id").asText());
    }

    private Map<String, Object> location(String label, String timezone) {
        Map<String, Object> location = new HashMap<>();
        location.put("label", label);
        location.put("line1", "400 Sunset Blvd");
        location.put("city", "Los Angeles");
        location.put("region", "CA");
        location.put("postalCode", "90028");
        location.put("country", "US");
        if (timezone != null) {
            location.put("timezone", timezone);
        }
        location.put("isPrimary", false);
        return location;
    }

    private Map<String, Object> baseWindow(UUID customer) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("customerId", customer.toString());
        body.put("locationId", locationOf(owner, customer).toString());
        body.put("technicianIds", List.of(owner.userId().toString()));
        body.put("title", "Window test visit");
        return body;
    }

    private JsonNode book(UUID customer, String startLocal, String endLocal, int expected) throws Exception {
        Map<String, Object> body = baseWindow(customer);
        body.put("startLocal", startLocal);
        body.put("endLocal", endLocal);
        return postJson("/api/v1/jobs", body, owner, expected);
    }
}