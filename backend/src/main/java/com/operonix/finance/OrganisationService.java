package com.operonix.finance;

import com.operonix.audit.AuditAction;
import com.operonix.audit.AuditService;
import com.operonix.common.error.ApiException;
import com.operonix.common.error.ErrorCode;
import com.operonix.common.money.MoneyCalculator;
import com.operonix.global.Currency;
import com.operonix.global.ReferenceDataService;
import com.operonix.security.CurrentUser;
import com.operonix.tenancy.Tenant;
import com.operonix.tenancy.TenantService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Legal entities, business locations and workspace financial settings (LLD §17.2). */
@Service
public class OrganisationService {

    private final LegalEntityRepository legalEntities;
    private final BusinessLocationRepository locations;
    private final TenantFinancialSettingsRepository settings;
    private final TenantService tenants;
    private final ReferenceDataService referenceData;
    private final AuditService audit;
    private final CurrentUser currentUser;

    public OrganisationService(LegalEntityRepository legalEntities, BusinessLocationRepository locations,
                               TenantFinancialSettingsRepository settings, TenantService tenants,
                               ReferenceDataService referenceData, AuditService audit,
                               CurrentUser currentUser) {
        this.legalEntities = legalEntities;
        this.locations = locations;
        this.settings = settings;
        this.tenants = tenants;
        this.referenceData = referenceData;
        this.audit = audit;
        this.currentUser = currentUser;
    }

    // ------------------------------------------------------------- legal entities

    @Transactional(readOnly = true)
    public List<FinanceDtos.LegalEntityResponse> listEntities() {
        return legalEntities.findAllByTenantIdOrderByDefaultEntityDescNameAsc(currentUser.tenantId()).stream()
                .map(FinanceDtos.LegalEntityResponse::from)
                .toList();
    }

    @Transactional
    public FinanceDtos.LegalEntityResponse createEntity(FinanceDtos.LegalEntityRequest request) {
        UUID tenantId = currentUser.tenantId();
        String name = request.name().trim();
        if (legalEntities.existsByTenantIdAndNameIgnoreCase(tenantId, name)) {
            throw ApiException.validation("name", "an entity with this name already exists");
        }
        LegalEntity entity = new LegalEntity();
        entity.setTenantId(tenantId);
        apply(entity, request, name);
        boolean first = legalEntities.countByTenantId(tenantId) == 0L;
        entity.setDefaultEntity(Boolean.TRUE.equals(request.makeDefault()) || first);
        if (entity.isDefaultEntity()) {
            clearDefaultEntity(tenantId);
        }
        legalEntities.save(entity);
        audit.record(AuditAction.WORKSPACE_UPDATED, "legal_entity", entity.getId(),
                Map.of("name", entity.getName(), "section", "legal-entity"));
        return FinanceDtos.LegalEntityResponse.from(entity);
    }

    @Transactional
    public FinanceDtos.LegalEntityResponse updateEntity(UUID id, FinanceDtos.LegalEntityRequest request) {
        UUID tenantId = currentUser.tenantId();
        LegalEntity entity = requireEntity(tenantId, id);
        String name = request.name().trim();
        if (legalEntities.existsByTenantIdAndNameIgnoreCaseAndIdNot(tenantId, name, id)) {
            throw ApiException.validation("name", "an entity with this name already exists");
        }
        apply(entity, request, name);
        if (Boolean.TRUE.equals(request.makeDefault())) {
            clearDefaultEntity(tenantId);
            entity.setDefaultEntity(true);
        }
        legalEntities.save(entity);
        audit.record(AuditAction.WORKSPACE_UPDATED, "legal_entity", entity.getId(),
                Map.of("name", entity.getName(), "section", "legal-entity"));
        return FinanceDtos.LegalEntityResponse.from(entity);
    }

    @Transactional
    public void deleteEntity(UUID id) {
        UUID tenantId = currentUser.tenantId();
        LegalEntity entity = requireEntity(tenantId, id);
        if (entity.isDefaultEntity()) {
            throw ApiException.conflict(ErrorCode.CONFLICT,
                    "choose another default entity before removing this one");
        }
        if (locations.countByTenantIdAndLegalEntityId(tenantId, id) > 0) {
            throw ApiException.conflict(ErrorCode.CONFLICT,
                    "this entity still has business locations; move or remove them first");
        }
        // Documents reference the entity, so it is retired rather than deleted.
        entity.setActive(false);
        entity.setDefaultEntity(false);
        legalEntities.save(entity);
    }

    @Transactional(readOnly = true)
    public LegalEntity requireEntity(UUID tenantId, UUID id) {
        return legalEntities.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> ApiException.notFound("Legal entity"));
    }

    @Transactional(readOnly = true)
    public LegalEntity defaultEntity(UUID tenantId) {
        return legalEntities.findFirstByTenantIdAndDefaultEntityTrue(tenantId).orElse(null);
    }

    // ------------------------------------------------------------------ locations

    @Transactional(readOnly = true)
    public List<FinanceDtos.LocationResponse> listLocations() {
        return locations.findAllByTenantIdOrderByDefaultLocationDescNameAsc(currentUser.tenantId()).stream()
                .map(FinanceDtos.LocationResponse::from)
                .toList();
    }

    @Transactional
    public FinanceDtos.LocationResponse createLocation(FinanceDtos.LocationRequest request) {
        UUID tenantId = currentUser.tenantId();
        String name = request.name().trim();
        if (locations.existsByTenantIdAndNameIgnoreCase(tenantId, name)) {
            throw ApiException.validation("name", "a location with this name already exists");
        }
        BusinessLocation location = new BusinessLocation();
        location.setTenantId(tenantId);
        apply(location, request, name, tenantId);
        location.setDefaultLocation(Boolean.TRUE.equals(request.makeDefault())
                || locations.findAllByTenantIdOrderByDefaultLocationDescNameAsc(tenantId).isEmpty());
        if (location.isDefaultLocation()) {
            clearDefaultLocation(tenantId);
        }
        locations.save(location);
        audit.record(AuditAction.WORKSPACE_UPDATED, "business_location", location.getId(),
                Map.of("name", location.getName(), "section", "location"));
        return FinanceDtos.LocationResponse.from(location);
    }

    @Transactional
    public FinanceDtos.LocationResponse updateLocation(UUID id, FinanceDtos.LocationRequest request) {
        UUID tenantId = currentUser.tenantId();
        BusinessLocation location = requireLocation(tenantId, id);
        String name = request.name().trim();
        if (locations.existsByTenantIdAndNameIgnoreCaseAndIdNot(tenantId, name, id)) {
            throw ApiException.validation("name", "a location with this name already exists");
        }
        apply(location, request, name, tenantId);
        if (Boolean.TRUE.equals(request.makeDefault())) {
            clearDefaultLocation(tenantId);
            location.setDefaultLocation(true);
        }
        locations.save(location);
        audit.record(AuditAction.WORKSPACE_UPDATED, "business_location", location.getId(),
                Map.of("name", location.getName(), "section", "location"));
        return FinanceDtos.LocationResponse.from(location);
    }

    @Transactional
    public void deleteLocation(UUID id) {
        UUID tenantId = currentUser.tenantId();
        BusinessLocation location = requireLocation(tenantId, id);
        if (location.isDefaultLocation()) {
            throw ApiException.conflict(ErrorCode.CONFLICT,
                    "choose another default location before removing this one");
        }
        location.setActive(false);
        location.setDefaultLocation(false);
        locations.save(location);
    }

    private BusinessLocation requireLocation(UUID tenantId, UUID id) {
        return locations.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> ApiException.notFound("Business location"));
    }

    // ------------------------------------------------------------------- settings

    @Transactional(readOnly = true)
    public FinanceDtos.SettingsResponse getSettings() {
        UUID tenantId = currentUser.tenantId();
        Tenant tenant = tenants.require(tenantId);
        TenantFinancialSettings row = settings.findById(tenantId).orElseGet(() -> defaults(tenant));
        return toResponse(row, tenant);
    }

    @Transactional
    public FinanceDtos.SettingsResponse updateSettings(FinanceDtos.SettingsRequest request) {
        UUID tenantId = currentUser.tenantId();
        Tenant tenant = tenants.require(tenantId);
        TenantFinancialSettings row = settings.findById(tenantId).orElseGet(() -> defaults(tenant));

        if (request.defaultTaxProfileId() != null) {
            row.setDefaultTaxProfileId(request.defaultTaxProfileId());
        }
        if (request.defaultLegalEntityId() != null) {
            requireEntity(tenantId, request.defaultLegalEntityId());
            row.setDefaultLegalEntityId(request.defaultLegalEntityId());
        }
        if (request.fiscalYearStartMonth() != null) {
            row.setFiscalYearStartMonth(request.fiscalYearStartMonth().shortValue());
        }
        if (request.weekStart() != null) {
            row.setWeekStart(request.weekStart().shortValue());
        }
        if (request.locale() != null) {
            row.setLocale(request.locale().trim());
        }
        if (request.dateFormat() != null && !request.dateFormat().isBlank()) {
            row.setDateFormat(request.dateFormat().trim().toUpperCase(Locale.ROOT));
        }
        if (request.numberFormat() != null && !request.numberFormat().isBlank()) {
            row.setNumberFormat(request.numberFormat().trim().toUpperCase(Locale.ROOT));
        }
        settings.save(row);
        audit.record(AuditAction.WORKSPACE_UPDATED, "tenant_settings", tenantId,
                Map.of("section", "financial-settings"));
        return toResponse(row, tenant);
    }

    @Transactional
    public TenantFinancialSettings ensureSettings(Tenant tenant) {
        return settings.findById(tenant.getId()).orElseGet(() -> {
            TenantFinancialSettings row = defaults(tenant);
            return settings.save(row);
        });
    }

    // ------------------------------------------------------------------ internals

    private void apply(LegalEntity entity, FinanceDtos.LegalEntityRequest request, String name) {
        referenceData.requireCountry(request.countryCode());
        Currency currency = referenceData.requireCurrency(request.baseCurrency().trim().toUpperCase(Locale.ROOT));
        entity.setName(name);
        entity.setCountryCode(request.countryCode().trim().toUpperCase(Locale.ROOT));
        entity.setTaxNumber(trimToNull(request.taxNumber()));
        entity.setBaseCurrency(currency.getCode());
        entity.setTaxMode(request.taxMode() == null ? "EXCLUSIVE" : request.taxMode());
        entity.setTaxNumberLabel(trimToNull(request.taxNumberLabel()));
        entity.setAddress(trimToNull(request.address()));
    }

    private void apply(BusinessLocation location, FinanceDtos.LocationRequest request, String name,
                       UUID tenantId) {
        referenceData.requireCountry(request.countryCode());
        UUID entityId = request.legalEntityId() != null ? request.legalEntityId()
                : defaultEntity(tenantId) == null ? null : defaultEntity(tenantId).getId();
        if (entityId == null) {
            throw ApiException.validation("legalEntityId", "create a legal entity first");
        }
        requireEntity(tenantId, entityId);
        String timezone = request.timezone().trim();
        try {
            ZoneId.of(timezone);
        } catch (RuntimeException ex) {
            throw ApiException.validation("timezone", "is not a valid IANA time zone");
        }
        location.setLegalEntityId(entityId);
        location.setName(name);
        location.setCountryCode(request.countryCode().trim().toUpperCase(Locale.ROOT));
        location.setTimezone(timezone);
        location.setAddress(trimToNull(request.address()));
        if (request.active() != null) {
            location.setActive(request.active());
        }
    }

    private TenantFinancialSettings defaults(Tenant tenant) {
        TenantFinancialSettings row = new TenantFinancialSettings();
        row.setTenantId(tenant.getId());
        row.setLocale(tenant.getLocale());
        row.setCreatedAt(java.time.Instant.now());
        return row;
    }

    private void clearDefaultEntity(UUID tenantId) {
        for (LegalEntity existing : legalEntities.findAllByTenantIdOrderByDefaultEntityDescNameAsc(tenantId)) {
            if (existing.isDefaultEntity()) {
                existing.setDefaultEntity(false);
                legalEntities.save(existing);
            }
        }
    }

    private void clearDefaultLocation(UUID tenantId) {
        for (BusinessLocation existing : locations.findAllByTenantIdOrderByDefaultLocationDescNameAsc(tenantId)) {
            if (existing.isDefaultLocation()) {
                existing.setDefaultLocation(false);
                locations.save(existing);
            }
        }
    }

    private FinanceDtos.SettingsResponse toResponse(TenantFinancialSettings row, Tenant tenant) {
        String currency = row.getDefaultLegalEntityId() == null ? tenant.getCurrency()
                : legalEntities.findByIdAndTenantId(row.getDefaultLegalEntityId(), tenant.getId())
                .map(LegalEntity::getBaseCurrency)
                .orElse(tenant.getCurrency());
        int precision = referenceData.requireCurrency(currency).getMinorUnits();
        return new FinanceDtos.SettingsResponse(tenant.getId(), row.getLocale(), currency, precision,
                MoneyCalculator.TaxMode.EXCLUSIVE.name(), row.getDefaultTaxProfileId(),
                row.getDefaultLegalEntityId(), row.getFiscalYearStartMonth(), row.getWeekStart(),
                row.getDateFormat(), row.getNumberFormat());
    }

    /** Convenience for document services that need the workspace base currency. */
    public String baseCurrency(UUID tenantId, Tenant tenant) {
        LegalEntity entity = defaultEntity(tenantId);
        return entity == null ? tenant.getCurrency() : entity.getBaseCurrency();
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
