package com.operonix.catalog;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ServiceItemRepository extends JpaRepository<ServiceItem, UUID> {

    Optional<ServiceItem> findByIdAndTenantId(UUID id, UUID tenantId);

    boolean existsByTenantIdAndNameIgnoreCase(UUID tenantId, String name);

    boolean existsByTenantIdAndNameIgnoreCaseAndIdNot(UUID tenantId, String name, UUID id);

    @Query("""
            select s from ServiceItem s
            where s.tenantId = :tenantId
              and (:active is null or s.active = :active)
              and (:q is null or lower(s.name) like :q or lower(coalesce(s.description, '')) like :q)
            order by s.name asc
            """)
    List<ServiceItem> search(@Param("tenantId") UUID tenantId, @Param("active") Boolean active,
                             @Param("q") String q);
}
