package com.operonix.finance;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TaxDefinitionRepository extends JpaRepository<TaxDefinition, UUID> {

    List<TaxDefinition> findAllByActiveTrueOrderByCountryCodeAscNameAsc();

    Optional<TaxDefinition> findByCode(String code);
}
