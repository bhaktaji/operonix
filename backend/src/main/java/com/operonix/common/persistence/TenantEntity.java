package com.operonix.common.persistence;

import com.operonix.security.TenantContext;
import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/**
 * Every tenant-owned aggregate carries its tenant id. The pre-persist guard fails the write if a
 * row is stamped with a tenant other than the request's tenant, catching isolation bugs early.
 */
@MappedSuperclass
@Getter
@Setter
public abstract class TenantEntity extends BaseEntity {

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @PrePersist
    void enforceTenantOnCreate() {
        if (tenantId == null) {
            throw new IllegalStateException("tenantId is required for tenant-owned entities");
        }
        UUID contextTenant = TenantContext.currentOrNull();
        if (contextTenant != null && !contextTenant.equals(tenantId)) {
            throw new IllegalStateException("Cross-tenant write blocked: entity tenant differs from request tenant");
        }
    }
}
