package com.operonix.identity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    /** Every membership for an email. Email is unique per workspace, not globally. */
    @Query("select u from AppUser u where lower(u.email) = lower(:email) order by u.createdAt asc")
    List<AppUser> findAllByEmail(@Param("email") String email);

    @Query("select count(u) > 0 from AppUser u where u.tenantId = :tenantId and lower(u.email) = lower(:email)")
    boolean existsByTenantIdAndEmail(@Param("tenantId") UUID tenantId, @Param("email") String email);

    Optional<AppUser> findByIdAndTenantId(UUID id, UUID tenantId);

    List<AppUser> findAllByTenantIdOrderByFullNameAsc(UUID tenantId);

    List<AppUser> findAllByTenantIdAndIdIn(UUID tenantId, Collection<UUID> ids);

    long countByTenantIdAndRoleAndActiveTrue(UUID tenantId, Role role);

    long countByTenantId(UUID tenantId);
}