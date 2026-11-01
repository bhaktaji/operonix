package com.operonix.scheduling;

import com.operonix.common.persistence.TenantEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "job")
@Getter
@Setter
@NoArgsConstructor
public class Job extends TenantEntity {

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(name = "location_id", nullable = false)
    private UUID locationId;

    @Column(name = "quote_id")
    private UUID quoteId;

    @Column(name = "number", nullable = false, length = 20)
    private String number;

    @Column(name = "title", nullable = false, length = 160)
    private String title;

    @Column(name = "scheduled_start", nullable = false)
    private Instant scheduledStart;

    @Column(name = "scheduled_end", nullable = false)
    private Instant scheduledEnd;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private JobStatus status = JobStatus.SCHEDULED;

    @Enumerated(EnumType.STRING)
    @Column(name = "recurrence", nullable = false, length = 16)
    private Recurrence recurrence = Recurrence.NONE;

    @Column(name = "series_id")
    private UUID seriesId;

    @Column(name = "notes", length = 2000)
    private String notes;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "completed_at")
    private Instant completedAt;

    /** Earliest work-log start. Derived, so a job reports its real start rather than its plan. */
    @Column(name = "actual_start")
    private Instant actualStart;

    @Column(name = "actual_end")
    private Instant actualEnd;

    @OneToMany(mappedBy = "job", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<JobAssignment> assignments = new ArrayList<>();

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    public void addAssignment(JobAssignment assignment) {
        assignment.setJob(this);
        assignment.setTenantId(getTenantId());
        assignments.add(assignment);
    }

    public void removeAssignment(JobAssignment assignment) {
        assignments.remove(assignment);
    }

    /** Derive duration in minutes from actual_start/actual_end when work logs are absent. */
    public Integer computeDurationMinutes() {
        if (actualStart != null && actualEnd != null) {
            return (int) Duration.between(actualStart, actualEnd).toMinutes();
        }
        return null;
    }

}