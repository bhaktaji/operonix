package com.operonix.customer;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ServiceLocationRepository extends JpaRepository<ServiceLocation, UUID> {

    Optional<ServiceLocation> findByIdAndTenantId(UUID id, UUID tenantId);

    List<ServiceLocation> findAllByTenantIdAndCustomerIdOrderByPrimaryDescLabelAsc(UUID tenantId,
                                                                                 UUID customerId);

    long countByTenantIdAndCustomerId(UUID tenantId, UUID customerId);

    List<ServiceLocation> findAllByTenantIdAndIdIn(UUID tenantId, Collection<UUID> ids);

    @Modifying
    @Query("update ServiceLocation l set l.primary = false where l.customerId = :customerId and l.primary = true")
    void clearPrimary(@Param("customerId") UUID customerId);
}
