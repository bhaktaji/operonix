package com.operonix.security;

import java.util.UUID;

/**
 * Request-scoped tenant discriminator (LLD §8). Populated by {@link JwtAuthenticationFilter}
 * from the verified token and cleared in a finally block. Never populated from client input.
 */
public final class TenantContext {

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(UUID tenantId) {
        CURRENT.set(tenantId);
    }

    public static UUID currentOrNull() {
        return CURRENT.get();
    }

    public static UUID requireTenantId() {
        UUID tenantId = CURRENT.get();
        if (tenantId == null) {
            throw new IllegalStateException("No tenant in context for a tenant-scoped operation");
        }
        return tenantId;
    }

    public static void clear() {
        CURRENT.remove();
    }

    /** Runs an action under a specific tenant, restoring the previous context afterwards. */
    public static <T> T runWith(UUID tenantId, java.util.function.Supplier<T> action) {
        UUID previous = CURRENT.get();
        CURRENT.set(tenantId);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
