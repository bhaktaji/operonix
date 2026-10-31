package com.operonix.tenancy;

import com.operonix.common.error.ApiException;
import com.operonix.common.util.Slugs;
import com.operonix.tenancy.dto.TenantSummary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class TenantService {

    private final TenantRepository repository;

    public TenantService(TenantRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Tenant require(UUID tenantId) {
        return repository.findById(tenantId).orElseThrow(() -> ApiException.notFound("Workspace"));
    }

    @Transactional(readOnly = true)
    public TenantSummary summary(UUID tenantId) {
        return TenantSummary.from(require(tenantId));
    }

    /** Creates a workspace with a unique slug. Always called inside a larger provisioning transaction. */
    @Transactional
    public Tenant provision(String name) {
        Tenant tenant = new Tenant();
        tenant.setName(name.trim());
        tenant.setSlug(uniqueSlug(name));
        tenant.setCurrency("USD");
        tenant.setTimezone("UTC");
        return repository.save(tenant);
    }

    @Transactional
    public Tenant update(UUID tenantId, String name, String currency, String timezone) {
        Tenant tenant = require(tenantId);
        tenant.setName(name.trim());
        tenant.setCurrency(currency.toUpperCase());
        tenant.setTimezone(timezone);
        return tenant;
    }

    private String uniqueSlug(String name) {
        String base = Slugs.fromName(name);
        String candidate = base;
        for (int suffix = 2; suffix <= 99; suffix++) {
            if (!repository.existsBySlug(candidate)) {
                return candidate;
            }
            candidate = base + "-" + suffix;
        }
        return base + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
