package com.operonix.finance;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface TaxComponentRepository extends JpaRepository<TaxComponent, UUID> {

    List<TaxComponent> findAllByTaxDefinitionIdOrderByPositionAsc(UUID taxDefinitionId);

    List<TaxComponent> findAllByTaxDefinitionIdInOrderByTaxDefinitionIdAscPositionAsc(
            Collection<UUID> taxDefinitionIds);
}
