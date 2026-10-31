package com.operonix.security;

import com.operonix.identity.Role;

import java.util.UUID;

/** The verified identity behind a request. Derived only from the JWT, never from request input. */
public record AuthenticatedUser(UUID id, UUID tenantId, Role role, String email, String fullName) {

    public boolean isTechnician() {
        return role == Role.TECHNICIAN;
    }

    public boolean isManagerOrAbove() {
        return role.isManagerOrAbove();
    }
}
