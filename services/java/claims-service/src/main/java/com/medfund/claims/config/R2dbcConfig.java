package com.medfund.claims.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.r2dbc.config.EnableR2dbcAuditing;
import org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories;

@Configuration
@EnableR2dbcRepositories(basePackages = {
        "com.medfund.claims.repository",
        "com.medfund.shared.scheduler",
        // NotificationRepository — required wherever JobEventPublisher loads.
        "com.medfund.shared.notification",
        // TenantRuleRepository — claims-service reads tenant rules to feed
        // the per-tenant Drools engine during the AdjudicationPipeline's
        // tenant-rules stage.
        "com.medfund.rules.repository",
})
@EnableR2dbcAuditing
public class R2dbcConfig {}
