package com.operonix.analytics;

import com.operonix.tenancy.Tenant;
import com.operonix.tenancy.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * One-shot analytics refresh for Cloud Run Jobs.
 *
 * <p>Cloud Run Jobs are the right home for scheduled aggregation: the container starts, rebuilds the
 * rollup for every workspace, and exits. Enable with
 * {@code OPERONIX_ANALYTICS_RUN_ON_STARTUP=true} and {@code OPERONIX_ANALYTICS_EXIT_AFTER_RUN=true};
 * the second flag stops the web server so the job completes instead of lingering.
 */
@Component
@ConditionalOnProperty(name = "operonix.analytics.run-on-startup", havingValue = "true")
public class AnalyticsJobRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsJobRunner.class);

    private final AnalyticsService analytics;
    private final TenantRepository tenants;
    private final org.springframework.context.ConfigurableApplicationContext context;

    public AnalyticsJobRunner(AnalyticsService analytics, TenantRepository tenants,
                              org.springframework.context.ConfigurableApplicationContext context) {
        this.analytics = analytics;
        this.tenants = tenants;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        int refreshed = 0;
        int failed = 0;
        for (Tenant tenant : tenants.findAll()) {
            try {
                analytics.refreshWorkspace(tenant.getId());
                refreshed++;
            } catch (RuntimeException ex) {
                failed++;
                log.error("Analytics refresh failed for workspace {}", tenant.getId(), ex);
            }
        }
        log.info("Analytics job finished: {} refreshed, {} failed", refreshed, failed);
        if (context.getEnvironment().getProperty("operonix.analytics.exit-after-run", Boolean.class,
                Boolean.FALSE)) {
            int code = failed == 0 ? 0 : 1;
            log.info("Exiting analytics job with status {}", code);
            System.exit(org.springframework.boot.SpringApplication.exit(context, () -> code));
        }
    }
}
