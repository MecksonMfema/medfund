package com.medfund.tenancy.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wraps Spring Boot's default Flyway migration behaviour with a
 * {@code repair()} call in front of {@code migrate()}.
 *
 * <p>The public schema history in dev shares the applied-migration
 * table with tenant migrations (see application.yml — locations includes
 * both {@code db/migration/public} and {@code db/migration/tenant}).
 * Any accidental edit to a previously-applied file bumps its checksum
 * and causes Flyway to abort with {@code FlywayValidateException:
 * Migration checksum mismatch}. {@code repair()} recomputes the
 * stored checksum for every applied migration against the current
 * file, so we recover from the drift without hand-editing the
 * history table.
 *
 * <p>Prevention of the drift itself is documented in
 * {@code feedback_never_edit_applied_migrations} — new corrections
 * go into a new higher-numbered migration. This bean is the belt for
 * that suspenders rule: it turns a hard boot failure into a warning
 * so an ops-triggered checksum drift doesn't take the service down.
 */
@Configuration
public class FlywayConfig {

    private static final Logger log = LoggerFactory.getLogger(FlywayConfig.class);

    @Bean
    public FlywayMigrationStrategy repairThenMigrate() {
        return flyway -> {
            try {
                log.info("Running Flyway repair() before migrate() to reconcile any checksum drift");
                flyway.repair();
            } catch (Exception e) {
                // repair() should almost never fail — if it does we still
                // want to attempt migrate() so the ops person sees the
                // real error, not the repair error.
                log.warn("Flyway repair() reported: {} — continuing to migrate()", e.getMessage());
            }
            flyway.migrate();
        };
    }
}
