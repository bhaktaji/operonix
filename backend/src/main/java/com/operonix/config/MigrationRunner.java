package com.operonix.config;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * Migrate-then-exit mode for controlled deployments.
 *
 * <p>The service itself migrates on startup (Flyway is idempotent and lock-protected), so a normal
 * rollout is safe on its own. This mode exists for deployments that want the schema change to land
 * and be observed <em>before</em> a new revision starts serving traffic:
 *
 * <pre>
 * java -jar app.jar --spring.profiles.active=prod \
 *   --spring.main.web-application-type=none --operonix.migrate-only=true
 * </pre>
 *
 * <p>Flyway runs as part of context startup, so a failed migration fails the context and the process
 * exits non-zero. On success the applied version is logged and the process exits 0. It is packaged as
 * the {@code operonix-migrate} Cloud Run job (see deploy/gcp/README.md). Disabled by default, so
 * tests and normal runs are unaffected.
 */
@Component
@ConditionalOnProperty(prefix = "operonix", name = "migrate-only", havingValue = "true")
public class MigrationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MigrationRunner.class);

    private final Flyway flyway;
    private final ApplicationContext context;

    public MigrationRunner(Flyway flyway, ApplicationContext context) {
        this.flyway = flyway;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        MigrationInfo current = flyway.info().current();
        int applied = flyway.info().applied().length;
        log.info("Flyway migrations complete: current version={} description={} appliedMigrations={}",
                current == null ? "none" : current.getVersion(),
                current == null ? "none" : current.getDescription(),
                applied);
        System.exit(SpringApplication.exit(context, () -> 0));
    }
}
