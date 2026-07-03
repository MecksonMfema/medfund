package com.medfund.finance.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.r2dbc.config.EnableR2dbcAuditing;
import org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories;

@Configuration
@EnableR2dbcRepositories(basePackages = {
        "com.medfund.finance.repository",
        "com.medfund.shared.scheduler",
        // NotificationRepository — required wherever JobEventPublisher loads.
        "com.medfund.shared.notification",
        // TenantRuleRepository — finance-service runs PROVIDER_PAYMENT /
        // RECONCILIATION rules through the engine when scheduling runs.
        "com.medfund.rules.repository",
})
@EnableR2dbcAuditing
public class R2dbcConfig {}
