package com.operonix.identity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    Optional<AppUser> findByEmail(String email);

    boolean existsByEmail(String email);

    Optional<AppUser> findByIdAndTenantId(UUID id, UUID tenantId);

    List<AppUser> findAllByTenantIdOrderByFullNameAsc(UUID tenantId);

    List<AppUser> findAllByTenantIdAndIdIn(UUID tenantId, Collection<UUID> ids);

    long countByTenantIdAndRoleAndActiveTrue(UUID tenantId, Role role);

    long countByTenantId(UUID tenantId);
}
