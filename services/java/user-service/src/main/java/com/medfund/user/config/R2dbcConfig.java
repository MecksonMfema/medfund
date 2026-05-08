package com.medfund.user.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.r2dbc.config.EnableR2dbcAuditing;
import org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories;

@Configuration
@EnableR2dbcRepositories(basePackages = {
        "com.medfund.user.repository",
        "com.medfund.shared.scheduler",
        // TenantRuleRepository — user-service runs MEMBER_LIFECYCLE / AGE_GROUP /
        // UNDERWRITING rules through the engine on enrollment + termination.
        "com.medfund.rules.repository",
})
@EnableR2dbcAuditing
public class R2dbcConfig {
}
