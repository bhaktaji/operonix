package com.operonix.global;

import com.operonix.scheduling.WorkLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkLogRepository extends JpaRepository<WorkLog, UUID> {

    List<WorkLog> findByTenantIdAndJobIdOrderByStartAtAsc(UUID tenantId, UUID jobId);

    Optional<WorkLog> findByIdAndTenantId(UUID id, UUID tenantId);

    @Query("""
            select w from WorkLog w
            where w.tenantId = :tenantId and w.job.id = :jobId
              and w.appUser.id = :userId and w.endAt is null
            """)
    Optional<WorkLog> findOpenForUser(@Param("tenantId") UUID tenantId, @Param("jobId") UUID jobId,
                                      @Param("userId") UUID userId);

    long countByTenantIdAndEndAtIsNull(UUID tenantId);

    List<WorkLog> findByTenantIdAndStartAtGreaterThanEqualAndStartAtLessThan(
            UUID tenantId, Instant from, Instant to);
}
