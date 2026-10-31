package com.operonix.tenancy.dto;

import com.operonix.tenancy.Tenant;

import java.util.UUID;

/**
 * What the client needs to render correctly: currency, timezone, locale and the industry vocabulary.
 * The tax number is deliberately absent — it never belongs in a session payload.
 */
public record TenantSummary(UUID id, String name, String slug, String currency, String timezone,
                            String countryCode, String locale, String industryTemplate,
                            String legalName) {

    public static TenantSummary from(Tenant tenant) {
        return new TenantSummary(tenant.getId(), tenant.getName(), tenant.getSlug(),
                tenant.getCurrency(), tenant.getTimezone(), tenant.getCountryCode(), tenant.getLocale(),
                tenant.getIndustryTemplate(), tenant.getLegalName());
    }
}