package com.operonix.tenancy.dto;

import com.operonix.tenancy.Tenant;

import java.util.UUID;

public record WorkspaceResponse(UUID id, String name, String slug, String currency, String timezone,
                                long users) {

    public static WorkspaceResponse from(Tenant tenant, long users) {
        return new WorkspaceResponse(tenant.getId(), tenant.getName(), tenant.getSlug(),
                tenant.getCurrency(), tenant.getTimezone(), users);
    }
}
