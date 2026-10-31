package com.operonix.tenancy;

import com.operonix.common.error.ApiException;
import com.operonix.common.util.Slugs;
import com.operonix.global.Country;
import com.operonix.global.Currency;
import com.operonix.global.IndustryTemplate;
import com.operonix.global.ReferenceDataService;
import com.operonix.tenancy.dto.TenantSummary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Locale;
import java.util.UUID;

@Service
public class TenantService {

    private final TenantRepository repository;
    private final ReferenceDataService referenceData;
    private final Clock clock;
    private final org.springframework.context.ApplicationEventPublisher events;

    public TenantService(TenantRepository repository, ReferenceDataService referenceData, Clock clock,
                         org.springframework.context.ApplicationEventPublisher events) {
        this.repository = repository;
        this.referenceData = referenceData;
        this.clock = clock;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public java.util.Optional<Tenant> find(UUID tenantId) {
        return repository.findById(tenantId);
    }

    @Transactional(readOnly = true)
    public Tenant require(UUID tenantId) {
        return repository.findById(tenantId).orElseThrow(() -> ApiException.notFound("Workspace"));
    }

    @Transactional(readOnly = true)
    public TenantSummary summary(UUID tenantId) {
        return TenantSummary.from(require(tenantId));
    }

    /**
     * Creates a workspace with a unique slug. Country drives currency, locale and timezone defaults so
     * onboarding can send only what the user actually chose.
     */
    @Transactional
    public Tenant provision(Provisioning provisioning) {
        Country country = referenceData.requireCountry(provisioning.countryCode());
        Currency currency = provisioning.currencyCode() == null || provisioning.currencyCode().isBlank()
                ? referenceData.requireCurrency(country.getDefaultCurrency())
                : referenceData.requireCurrency(provisioning.currencyCode());
        IndustryTemplate template = referenceData.resolveIndustry(provisioning.industryTemplate());

        Tenant tenant = new Tenant();
        tenant.setName(provisioning.name().trim());
        tenant.setSlug(uniqueSlug(provisioning.name()));
        tenant.setCountryCode(country.getCode());
        tenant.setCurrency(currency.getCode());
        tenant.setLocale(provisioning.locale() == null || provisioning.locale().isBlank()
                ? country.getDefaultLocale() : provisioning.locale().trim());
        tenant.setTimezone(provisioning.timezone() == null || provisioning.timezone().isBlank()
                ? "UTC" : requireZone(provisioning.timezone()));
        tenant.setIndustryTemplate(template.name());
        tenant.setHomeRegion(provisioning.homeRegion() == null || provisioning.homeRegion().isBlank()
                ? "asia-south1" : provisioning.homeRegion().trim());
        tenant.setLegalName(trimToNull(provisioning.legalName()));
        tenant.setTaxNumber(trimToNull(provisioning.taxNumber()));
        tenant.setOnboardedAt(clock.instant());
        Tenant saved = repository.save(tenant);
        // Finance seeds the default entity, tax profile, settings and location in the same transaction.
        events.publishEvent(new WorkspaceProvisionedEvent(saved.getId()));
        return saved;
    }

    @Transactional
    public Tenant update(UUID tenantId, WorkspaceChanges changes) {
        Tenant tenant = require(tenantId);
        Country country = referenceData.requireCountry(changes.countryCode());
        Currency currency = referenceData.requireCurrency(changes.currency());
        IndustryTemplate template = referenceData.resolveIndustry(changes.industryTemplate());

        tenant.setName(changes.name().trim());
        tenant.setCountryCode(country.getCode());
        tenant.setCurrency(currency.getCode());
        tenant.setTimezone(requireZone(changes.timezone()));
        tenant.setLocale(changes.locale().trim());
        tenant.setIndustryTemplate(template.name());
        tenant.setLegalName(trimToNull(changes.legalName()));
        tenant.setTaxNumber(trimToNull(changes.taxNumber()));
        return tenant;
    }

    private String requireZone(String timezone) {
        try {
            return ZoneId.of(timezone).getId();
        } catch (DateTimeException ex) {
            throw ApiException.validation("timezone", "must be a valid IANA timezone, e.g. Asia/Kolkata");
        }
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
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

    /** Inbound values for workspace creation. Nulls fall back to the country's defaults. */
    public record Provisioning(String name, String countryCode, String currencyCode, String timezone,
                               String locale, String industryTemplate, String homeRegion,
                               String legalName, String taxNumber) {
    }

    /** Inbound values for a workspace settings update; all required because the form is whole-object. */
    public record WorkspaceChanges(String name, String countryCode, String currency, String timezone,
                                   String locale, String industryTemplate, String legalName,
                                   String taxNumber) {
    }

    static String normaliseLocale(String locale) {
        return locale == null ? "" : locale.trim().replace('_', '-');
    }

    static boolean looksLikeLocale(String locale) {
        String normalised = normaliseLocale(locale).toLowerCase(Locale.ROOT);
        return normalised.matches("^[a-z]{2}(-[a-z]{2})?$");
    }
}