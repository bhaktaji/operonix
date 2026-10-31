package com.operonix.security;

import com.operonix.common.error.ApiException;
import com.operonix.common.error.ErrorCode;
import com.operonix.identity.Role;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
public class CurrentUser {

    public Optional<AuthenticatedUser> optional() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user) {
            return Optional.of(user);
        }
        return Optional.empty();
    }

    public AuthenticatedUser require() {
        return optional().orElseThrow(() -> ApiException.of(ErrorCode.UNAUTHENTICATED,
                "Authentication is required"));
    }

    public UUID tenantId() {
        return require().tenantId();
    }

    public UUID id() {
        return require().id();
    }

    public Role role() {
        return require().role();
    }
}
