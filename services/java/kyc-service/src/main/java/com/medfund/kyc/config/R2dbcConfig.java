package com.medfund.kyc.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.r2dbc.config.EnableR2dbcAuditing;
import org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories;

/**
 * R2DBC repository scanning for kyc-service. Spring Boot's auto-config scans
 * only the application package, so the shared + rules-engine repositories the
 * scanned beans depend on must be listed explicitly (same as every sibling
 * service's R2dbcConfig).
 *
 * <p>{@code @EnableR2dbcAuditing} drives the {@code @CreatedDate} /
 * {@code @LastModifiedDate} stamps on {@link com.medfund.kyc.entity.KycVerification}.
 */
@Configuration
@EnableR2dbcRepositories(basePackages = {
        "com.medfund.kyc.repository",
        // NotificationRepository — required wherever JobEventPublisher loads.
        "com.medfund.shared.scheduler",
        "com.medfund.shared.notification",
        // TenantRuleRepository — kyc-service runs VERIFICATION rules through the
        // engine (Plan A Phase 3 fact + category).
        "com.medfund.rules.repository",
})
@EnableR2dbcAuditing
public class R2dbcConfig {
}
