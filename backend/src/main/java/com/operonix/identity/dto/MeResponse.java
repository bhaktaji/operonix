package com.operonix.identity.dto;

import com.operonix.tenancy.dto.TenantSummary;

public record MeResponse(UserSummary user, TenantSummary tenant) {
}
