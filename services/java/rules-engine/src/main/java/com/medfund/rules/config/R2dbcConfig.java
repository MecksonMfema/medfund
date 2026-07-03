package com.medfund.rules.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.r2dbc.config.EnableR2dbcAuditing;
import org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories;

/**
 * Wires the R2DBC repository scan + auditing for tenant_rules.
 *
 * <p>The {@code shared.scheduler} package is included because
 * {@code com.medfund.shared.scheduler.JobDispatcher} is component-scanned in
 * (it lives in {@code shared}) and depends on {@code ScheduledJobRepository}.
 * Every Spring Boot service in the workspace mirrors this pattern.
 */
@Configuration
@EnableR2dbcRepositories(basePackages = {
        "com.medfund.rules.repository",
        "com.medfund.shared.scheduler",
        // NotificationRepository — required wherever JobEventPublisher loads.
        "com.medfund.shared.notification",
})
@EnableR2dbcAuditing
public class R2dbcConfig {}
