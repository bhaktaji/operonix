package com.operonix.finance;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BusinessLocationRepository extends JpaRepository<BusinessLocation, UUID> {

    List<BusinessLocation> findAllByTenantIdOrderByDefaultLocationDescNameAsc(UUID tenantId);

    Optional<BusinessLocation> findByIdAndTenantId(UUID id, UUID tenantId);

    Optional<BusinessLocation> findFirstByTenantIdAndDefaultLocationTrue(UUID tenantId);

    boolean existsByTenantIdAndNameIgnoreCase(UUID tenantId, String name);

    boolean existsByTenantIdAndNameIgnoreCaseAndIdNot(UUID tenantId, String name, UUID id);

    long countByTenantIdAndLegalEntityId(UUID tenantId, UUID legalEntityId);
}
