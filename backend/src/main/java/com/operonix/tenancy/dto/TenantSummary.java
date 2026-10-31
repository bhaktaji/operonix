package com.operonix.tenancy.dto;

import com.operonix.tenancy.Tenant;

import java.util.UUID;

public record TenantSummary(UUID id, String name, String slug, String currency, String timezone) {

    public static TenantSummary from(Tenant tenant) {
        return new TenantSummary(tenant.getId(), tenant.getName(), tenant.getSlug(),
                tenant.getCurrency(), tenant.getTimezone());
    }
}
