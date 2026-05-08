package com.medfund.contributions.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.r2dbc.config.EnableR2dbcAuditing;
import org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories;

@Configuration
@EnableR2dbcRepositories(basePackages = {
        "com.medfund.contributions.repository",
        "com.medfund.shared.scheduler",
        // TenantRuleRepository — contributions-service reads tenant rules to feed
        // the rules engine for CONTRIBUTION_PRICING / CONTRIBUTION_BILLING evaluation.
        "com.medfund.rules.repository",
})
@EnableR2dbcAuditing
public class R2dbcConfig {}
