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

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class WorkspaceService {

    private static final Set<String> CURRENCIES = Set.of("USD", "EUR", "GBP", "INR", "AUD", "CAD");

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
        String currency = request.currency().trim().toUpperCase(Locale.ROOT);
        if (!CURRENCIES.contains(currency)) {
            throw ApiException.validation("currency", "must be one of " + CURRENCIES);
        }
        try {
            ZoneId.of(request.timezone());
        } catch (DateTimeException ex) {
            throw ApiException.validation("timezone", "must be a valid IANA timezone, e.g. America/New_York");
        }
        var tenantId = currentUser.tenantId();
        Tenant tenant = tenantService.update(tenantId, request.name(), currency, request.timezone());
        audit.record(AuditAction.WORKSPACE_UPDATED, "tenant", tenant.getId(),
                Map.of("name", tenant.getName(), "currency", tenant.getCurrency(),
                        "timezone", tenant.getTimezone()));
        return WorkspaceResponse.from(tenant, users.countByTenantId(tenantId));
    }
}
