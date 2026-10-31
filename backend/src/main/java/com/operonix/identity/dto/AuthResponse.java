package com.operonix.identity.dto;

import com.operonix.tenancy.dto.TenantSummary;

public record AuthResponse(String accessToken, long expiresIn, String refreshToken,
                           UserSummary user, TenantSummary tenant) {
}
