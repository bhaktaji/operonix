package com.operonix.identity.dto;

import com.operonix.identity.AppUser;
import com.operonix.identity.Role;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TeamMemberResponse(UUID id, String email, String fullName, Role role, boolean active,
                                 BigDecimal hourlyCost, Instant lastLoginAt, Instant createdAt) {

    public static TeamMemberResponse from(AppUser user) {
        return new TeamMemberResponse(user.getId(), user.getEmail(), user.getFullName(), user.getRole(),
                user.isActive(), user.getHourlyCost(), user.getLastLoginAt(), user.getCreatedAt());
    }

    /** Name-only view used by scheduling pickers, where contact details are not needed. */
    public record Assignable(UUID id, String fullName) {

        public static Assignable from(AppUser user) {
            return new Assignable(user.getId(), user.getFullName());
        }
    }
}
