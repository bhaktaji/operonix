package com.operonix.scheduling;

import com.operonix.customer.CustomerActivityPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** Scheduling's answer to "is this customer or location still in live use?". */
@Component
public class CustomerActivityAdapter implements CustomerActivityPort {

    private static final List<JobStatus> LIVE_STATUSES = List.of(JobStatus.SCHEDULED, JobStatus.IN_PROGRESS);

    private final JobRepository jobs;

    public CustomerActivityAdapter(JobRepository jobs) {
        this.jobs = jobs;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasLiveJobs(UUID tenantId, UUID customerId) {
        return jobs.existsByTenantIdAndCustomerIdAndStatusIn(tenantId, customerId, LIVE_STATUSES);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isLocationInLiveUse(UUID tenantId, UUID customerId, UUID locationId) {
        return jobs.existsByTenantIdAndCustomerIdAndLocationIdAndStatusIn(tenantId, customerId,
                locationId, LIVE_STATUSES);
    }
}
