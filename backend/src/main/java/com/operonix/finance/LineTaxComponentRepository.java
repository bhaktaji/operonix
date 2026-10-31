package com.operonix.finance;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface LineTaxComponentRepository extends JpaRepository<LineTaxComponent, UUID> {

    List<LineTaxComponent> findAllByTenantIdAndLineTypeAndLineIdInOrderByPositionAsc(
            UUID tenantId, String lineType, Collection<UUID> lineIds);

    void deleteAllByTenantIdAndLineTypeAndLineIdIn(UUID tenantId, String lineType, Collection<UUID> lineIds);
}
