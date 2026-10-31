package com.operonix.scheduling;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JobRepository extends JpaRepository<Job, UUID>, JpaSpecificationExecutor<Job> {

    Optional<Job> findByIdAndTenantId(UUID id, UUID tenantId);

    @EntityGraph(attributePaths = "assignments")
    @Query("select j from Job j where j.id = :id and j.tenantId = :tenantId")
    Optional<Job> findDetailed(@Param("id") UUID id, @Param("tenantId") UUID tenantId);

    /**
     * Returns non-cancelled jobs assigned to any of the given users that overlap the window.
     * The half-open interval test (start &lt; otherEnd AND end &gt; otherStart) treats back-to-back
     * jobs as non-conflicting.
     */
    @Query("""
            select distinct j from Job j join j.assignments a
            where j.tenantId = :tenantId
              and a.userId in :userIds
              and j.status <> :cancelled
              and j.scheduledStart < :end
              and j.scheduledEnd > :start
              and (:excludeJobId is null or j.id <> :excludeJobId)
            """)
    List<Job> findConflicts(@Param("tenantId") UUID tenantId, @Param("userIds") Collection<UUID> userIds,
                            @Param("start") Instant start, @Param("end") Instant end,
                            @Param("cancelled") JobStatus cancelled,
                            @Param("excludeJobId") UUID excludeJobId);

    long countByTenantIdAndStatus(UUID tenantId, JobStatus status);

    boolean existsByTenantIdAndCustomerIdAndStatusIn(UUID tenantId, UUID customerId,
                                                     Collection<JobStatus> statuses);

    boolean existsByTenantIdAndCustomerIdAndLocationIdAndStatusIn(UUID tenantId, UUID customerId,
                                                                  UUID locationId,
                                                                  Collection<JobStatus> statuses);

    long countByTenantIdAndScheduledStartGreaterThanEqualAndScheduledStartLessThan(
            UUID tenantId, Instant from, Instant to);

    List<Job> findTop5ByTenantIdAndStatusAndScheduledStartGreaterThanEqualOrderByScheduledStartAsc(
            UUID tenantId, JobStatus status, Instant from);

    @EntityGraph(attributePaths = "assignments")
    @Query("""
            select j from Job j
            where j.tenantId = :tenantId
              and j.scheduledStart >= :from and j.scheduledStart < :to
              and j.status <> :cancelled
            """)
    List<Job> findAllInRange(@Param("tenantId") UUID tenantId, @Param("from") Instant from,
                             @Param("to") Instant to, @Param("cancelled") JobStatus cancelled);
}
