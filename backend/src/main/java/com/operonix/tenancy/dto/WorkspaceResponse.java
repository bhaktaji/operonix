package com.operonix.tenancy.dto;

import com.operonix.tenancy.Tenant;

import java.time.Instant;
import java.util.UUID;

public record WorkspaceResponse(UUID id, String name, String slug, String countryCode, String currency,
                                String timezone, String locale, String industryTemplate,
                                String legalName, String taxNumber, Instant onboardedAt, long users) {

    public static WorkspaceResponse from(Tenant tenant, long users) {
        return new WorkspaceResponse(tenant.getId(), tenant.getName(), tenant.getSlug(),
                tenant.getCountryCode(), tenant.getCurrency(), tenant.getTimezone(), tenant.getLocale(),
                tenant.getIndustryTemplate(), tenant.getLegalName(), tenant.getTaxNumber(),
                tenant.getOnboardedAt(), users);
    }
}