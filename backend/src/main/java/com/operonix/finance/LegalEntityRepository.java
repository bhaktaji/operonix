package com.operonix.finance;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LegalEntityRepository extends JpaRepository<LegalEntity, UUID> {

    List<LegalEntity> findAllByTenantIdOrderByDefaultEntityDescNameAsc(UUID tenantId);

    Optional<LegalEntity> findByIdAndTenantId(UUID id, UUID tenantId);

    Optional<LegalEntity> findFirstByTenantIdAndDefaultEntityTrue(UUID tenantId);

    boolean existsByTenantIdAndNameIgnoreCase(UUID tenantId, String name);

    boolean existsByTenantIdAndNameIgnoreCaseAndIdNot(UUID tenantId, String name, UUID id);

    long countByTenantId(UUID tenantId);
}
