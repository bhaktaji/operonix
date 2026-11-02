package com.operonix.scheduling.dto;

import com.operonix.customer.dto.CustomerDtos;
import com.operonix.scheduling.Job;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class JobDtos {

    private JobDtos() {
    }

    /**
     * A window can be sent either as UTC instants ({@code scheduledStart}/{@code scheduledEnd}) or as
     * wall-clock values ({@code startLocal}/{@code endLocal}), which the server resolves in the
     * location's time zone. The local form is what the UI sends, so a device in another zone cannot
     * shift the appointment.
     */
    public record ScheduleRequest(
            @NotNull UUID customerId,
            @NotNull UUID locationId,
            @Size(max = 160) String title,
            Instant scheduledStart,
            Instant scheduledEnd,
            @Size(max = 40) String startLocal,
            @Size(max = 40) String endLocal,
            List<UUID> technicianIds,
            @Size(max = 2000) String notes
    ) {
    }

    public record SeriesRequest(
            @NotNull UUID customerId,
            @NotNull UUID locationId,
            @Size(max = 160) String title,
            Instant scheduledStart,
            Instant scheduledEnd,
            @Size(max = 40) String startLocal,
            @Size(max = 40) String endLocal,
            List<UUID> technicianIds,
            @Size(max = 2000) String notes,
            @NotBlank String recurrence,
            @NotNull @Min(2) @Max(26) Integer occurrences
    ) {
    }

    public record AssignmentRequest(@NotNull UUID userId) {
    }

    public record TransitionRequest(@NotNull String to, @Size(max = 500) String note) {
    }

    public record TechnicianResponse(UUID id, String fullName) {
    }

    public record Conflict(UUID userId, String fullName, UUID jobId, String number,
                           Instant scheduledStart, Instant scheduledEnd) {
    }

    /** A resolved window: the instants that get stored, plus the zone they were read in. */
    public record Window(Instant start, Instant end, java.time.ZoneId zone) {
    }

    public record Response(UUID id, String number, String title, String status,
                           Instant scheduledStart, Instant scheduledEnd,
                           CustomerDtos.Summary customer, CustomerDtos.LocationResponse location,
                           UUID quoteId, String recurrence, UUID seriesId,
                           List<TechnicianResponse> technicians, String notes, Instant completedAt,
                           long version, Instant createdAt, Instant updatedAt) {

        public static Response from(Job job, CustomerDtos.Summary customer,
                                    CustomerDtos.LocationResponse location,
                                    List<TechnicianResponse> technicians) {
            return new Response(job.getId(), job.getNumber(), job.getTitle(), job.getStatus().name(),
                    job.getScheduledStart(), job.getScheduledEnd(), customer, location, job.getQuoteId(),
                    job.getRecurrence().name(), job.getSeriesId(), technicians, job.getNotes(),
                    job.getCompletedAt(), job.getVersion(), job.getCreatedAt(), job.getUpdatedAt());
        }
    }
}
