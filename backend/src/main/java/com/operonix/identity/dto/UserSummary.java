package com.operonix.identity.dto;

import com.operonix.identity.AppUser;
import com.operonix.identity.Role;

import java.util.UUID;

public record UserSummary(UUID id, String email, String fullName, Role role, UUID tenantId) {

    public static UserSummary from(AppUser user) {
        return new UserSummary(user.getId(), user.getEmail(), user.getFullName(), user.getRole(),
                user.getTenantId());
    }
}
