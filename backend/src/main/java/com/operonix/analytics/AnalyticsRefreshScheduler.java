package com.operonix.analytics;

import com.operonix.tenancy.Tenant;
import com.operonix.tenancy.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Rebuilds the analytics rollup for every workspace.
 *
 * <p>Off by default so a normal web instance never competes with request work. Two supported ways to
 * run it:
 *
 * <ul>
 *   <li>Cloud Run Job with {@code OPERONIX_ANALYTICS_SCHEDULER=true} and a single-instance task —
 *       the cleanest production option because the job exits when the refresh finishes.</li>
 *   <li>An in-process schedule by also setting {@code operonix.analytics.scheduler.cron}.</li>
 * </ul>
 *
 * <p>Reads are never blocked on this: a stale rollup is rebuilt on demand by the query path.
 */
@Component
@ConditionalOnProperty(name = "operonix.analytics.scheduler.enabled", havingValue = "true")
public class AnalyticsRefreshScheduler {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsRefreshScheduler.class);

    private final AnalyticsService analytics;
    private final TenantRepository tenants;

    public AnalyticsRefreshScheduler(AnalyticsService analytics, TenantRepository tenants) {
        this.analytics = analytics;
        this.tenants = tenants;
    }

    @Scheduled(cron = "${operonix.analytics.scheduler.cron:0 15 2 * * *}")
    public void refreshAllWorkspaces() {
        int workspaces = 0;
        for (Tenant tenant : tenants.findAll()) {
            try {
                analytics.refreshWorkspace(tenant.getId());
                workspaces++;
            } catch (RuntimeException ex) {
                // One bad workspace must not stop the others.
                log.warn("Analytics refresh failed for workspace {}", tenant.getId(), ex);
            }
        }
        log.info("Analytics rollup refreshed for {} workspace(s)", workspaces);
    }
}
