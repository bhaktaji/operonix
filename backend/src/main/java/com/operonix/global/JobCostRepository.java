package com.operonix.global;

import com.operonix.billing.InvoiceStatus;
import com.operonix.scheduling.JobStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JobCostRepository extends JpaRepository<JobCost, UUID> {

    Optional<JobCost> findByTenantIdAndJobId(UUID tenantId, UUID jobId);

    List<JobCost> findByTenantIdAndJobIdIn(UUID tenantId, List<UUID> jobIds);

    /**
     * Delivered-job profitability rows. Revenue is the sum of non-void invoices raised against the
     * job; cost is the roll-up. A job falls in the period by its actual end (falling back to the
     * scheduled end), so it is counted when it was delivered rather than when it was booked.
     */
    @Query("""
            select j.id, j.number, j.title, coalesce(j.actualEnd, j.scheduledEnd),
                   coalesce((select sum(i.total) from Invoice i
                              where i.jobId = j.id and i.tenantId = j.tenantId
                                and i.status <> :voidStatus), 0),
                   coalesce(c.totalCost, 0)
            from Job j left join JobCost c on c.job = j and c.tenantId = j.tenantId
            where j.tenantId = :tenantId and j.status = :completed
              and coalesce(j.actualEnd, j.scheduledEnd) >= :from
              and coalesce(j.actualEnd, j.scheduledEnd) < :to
            order by coalesce(j.actualEnd, j.scheduledEnd) desc
            """)
    List<Object[]> profitabilityRows(@Param("tenantId") UUID tenantId,
                                     @Param("from") Instant from, @Param("to") Instant to,
                                     @Param("completed") JobStatus completed,
                                     @Param("voidStatus") InvoiceStatus voidStatus);
}
