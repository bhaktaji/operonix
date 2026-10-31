package com.operonix.customer;

import java.util.UUID;

/**
 * Inward port implemented by the scheduling module so the customer module can refuse unsafe
 * archival/deletion without depending on scheduling internals (avoids a module cycle).
 */
public interface CustomerActivityPort {

    boolean hasLiveJobs(UUID tenantId, UUID customerId);

    boolean isLocationInLiveUse(UUID tenantId, UUID customerId, UUID locationId);
}
