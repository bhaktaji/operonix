package com.operonix.tenancy;

import com.operonix.audit.AuditAction;
import com.operonix.audit.AuditService;
import com.operonix.common.error.ApiException;
import com.operonix.identity.AppUserRepository;
import com.operonix.security.CurrentUser;
import com.operonix.tenancy.dto.UpdateWorkspaceRequest;
import com.operonix.tenancy.dto.WorkspaceResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Service
public class WorkspaceService {

    private final TenantService tenantService;
    private final AppUserRepository users;
    private final CurrentUser currentUser;
    private final AuditService audit;

    public WorkspaceService(TenantService tenantService, AppUserRepository users, CurrentUser currentUser,
                            AuditService audit) {
        this.tenantService = tenantService;
        this.users = users;
        this.currentUser = currentUser;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public WorkspaceResponse get() {
        var tenantId = currentUser.tenantId();
        return WorkspaceResponse.from(tenantService.require(tenantId), users.countByTenantId(tenantId));
    }

    @Transactional
    public WorkspaceResponse update(UpdateWorkspaceRequest request) {
        String locale = request.locale().trim();
        if (!TenantService.looksLikeLocale(locale)) {
            throw ApiException.validation("locale", "must be a BCP-47 tag such as en-GB");
        }
        var tenantId = currentUser.tenantId();
        Tenant tenant = tenantService.update(tenantId, new TenantService.WorkspaceChanges(
                request.name(),
                request.countryCode().trim().toUpperCase(Locale.ROOT),
                request.currency().trim().toUpperCase(Locale.ROOT),
                request.timezone().trim(),
                locale,
                request.industryTemplate().trim(),
                request.legalName(),
                request.taxNumber()));

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("name", tenant.getName());
        detail.put("country", tenant.getCountryCode());
        detail.put("currency", tenant.getCurrency());
        detail.put("timezone", tenant.getTimezone());
        detail.put("locale", tenant.getLocale());
        detail.put("industryTemplate", tenant.getIndustryTemplate());
        audit.record(AuditAction.WORKSPACE_UPDATED, "tenant", tenant.getId(), detail);

        return WorkspaceResponse.from(tenant, users.countByTenantId(tenantId));
    }
}