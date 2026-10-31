package com.operonix.tenancy;

import java.util.UUID;

/**
 * Published once a workspace row exists. Finance listens for it to seed the default legal entity, tax
 * profile, settings row and location.
 *
 * <p>An event keeps the dependency one-way: tenancy must not import finance, or the two modules would
 * depend on each other.
 */
public record WorkspaceProvisionedEvent(UUID tenantId) {
}
