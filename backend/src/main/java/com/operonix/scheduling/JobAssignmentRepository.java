package com.operonix.scheduling;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JobAssignmentRepository extends JpaRepository<JobAssignment, UUID> {

    List<JobAssignment> findAllByTenantIdAndJobIdIn(UUID tenantId, Collection<UUID> jobIds);

    Optional<JobAssignment> findByJobIdAndUserId(UUID jobId, UUID userId);

    boolean existsByJobIdAndUserId(UUID jobId, UUID userId);
}
