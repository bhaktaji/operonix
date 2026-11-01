package com.operonix.scheduling;

import com.operonix.common.persistence.TenantEntity;
import com.operonix.identity.AppUser;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * One technician's time on one job. A log is open until {@link #endAt} is set; only closed logs
 * contribute to labour cost, and an open log makes the job's profitability incomplete.
 */
@Entity
@Table(name = "work_log")
@Getter
@Setter
@NoArgsConstructor
public class WorkLog extends TenantEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_id", nullable = false)
    private Job job;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "app_user_id", nullable = false)
    private AppUser appUser;

    @Column(name = "start_at", nullable = false)
    private Instant startAt;

    @Column(name = "end_at")
    private Instant endAt;

    /** True when the end time was entered by hand rather than captured by start/stop. */
    @Column(name = "is_manual", nullable = false)
    private boolean manual;

    @Column(name = "notes", length = 2000)
    private String notes;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    public WorkLog(Job job, AppUser appUser, Instant startAt) {
        setTenantId(job.getTenantId());
        this.job = job;
        this.appUser = appUser;
        this.startAt = startAt;
    }

    public boolean isOpen() {
        return endAt == null;
    }

    /** Elapsed whole minutes, or null while the log is still open. */
    public Long minutes() {
        if (endAt == null) {
            return null;
        }
        return Duration.between(startAt, endAt).toMinutes();
    }
}
