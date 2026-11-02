package com.operonix.common.time;

import com.operonix.common.error.ApiException;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Resolves where a business wall-clock time happens.
 *
 * <p>A scheduled visit belongs to the time zone of the place it happens in: a Kolkata owner booking a
 * Los Angeles address is arranging the LA crew's morning, not their own. The location's IANA zone
 * therefore wins, and a location without one falls back to the workspace zone. The device's zone
 * never participates — it is not sent to the server and cannot influence a stored instant.
 *
 * <p>Daylight-saving edges follow the JDK's {@link LocalDateTime#atZone(ZoneId)} rules, which are the
 * conventional resolution: a wall-clock time inside a spring-forward gap moves forward by the gap
 * (02:30 becomes 03:30 local), and an ambiguous fall-back time takes the earlier offset (the first
 * occurrence).
 */
public final class WorkspaceZone {

    private WorkspaceZone() {
    }

    /** The zone to interpret a location's schedule in. */
    public static ZoneId of(String locationTimezone, String workspaceTimezone) {
        if (locationTimezone != null && !locationTimezone.isBlank()) {
            return requireValid(locationTimezone, "timezone");
        }
        if (workspaceTimezone == null || workspaceTimezone.isBlank()) {
            throw ApiException.validation("timezone", "the workspace has no time zone configured");
        }
        return requireValid(workspaceTimezone, "timezone");
    }

    /** Parses an IANA zone id, reporting a validation error rather than a 500 for unknown input. */
    public static ZoneId requireValid(String zoneId, String field) {
        try {
            return ZoneId.of(zoneId.trim());
        } catch (DateTimeException exception) {
            throw ApiException.validation(field, "must be an IANA time zone such as Asia/Kolkata");
        }
    }

    /** True when the value is a usable IANA zone id; used to keep bad input out of the database. */
    public static boolean isValid(String zoneId) {
        if (zoneId == null || zoneId.isBlank()) {
            return false;
        }
        try {
            ZoneId.of(zoneId.trim());
            return true;
        } catch (DateTimeException exception) {
            return false;
        }
    }

    /**
     * Converts a wall-clock value such as {@code 2026-11-02T09:00} in the given zone into the instant
     * that is stored. Seconds are optional; an offset or {@code Z} suffix is rejected, because such a
     * value is already an instant and must be sent as one.
     */
    public static Instant resolveLocal(String localDateTime, ZoneId zone, String field) {
        if (localDateTime == null || localDateTime.isBlank()) {
            throw ApiException.validation(field, "is required");
        }
        String value = localDateTime.trim();
        if (value.endsWith("Z") || value.matches(".*[+-]\\d{2}:?\\d{2}$")) {
            throw ApiException.validation(field,
                    "must be a local date-time without an offset; send an instant instead");
        }
        try {
            return LocalDateTime.parse(value).atZone(zone).toInstant();
        } catch (DateTimeException exception) {
            throw ApiException.validation(field, "must be an ISO date-time such as 2026-11-02T09:00");
        }
    }

    /** The wall-clock value of an instant in the given zone, as a {@code datetime-local} string. */
    public static String toLocalValue(Instant instant, ZoneId zone) {
        return LocalDateTime.ofInstant(instant, zone).toString();
    }
}
