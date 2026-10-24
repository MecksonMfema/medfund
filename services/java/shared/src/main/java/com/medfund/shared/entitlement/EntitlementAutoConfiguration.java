package com.medfund.shared.entitlement;

import com.medfund.shared.security.EntitlementAspect;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.r2dbc.core.DatabaseClient;

/**
 * Wires the {@link EntitlementAspect} into every service that depends on the
 * shared module. A plain {@code @Configuration} picked up by the
 * {@code com.medfund.shared} component scan — there is no
 * {@code AutoConfiguration.imports} file in this repo; this mirrors
 * {@code PermissionsAutoConfiguration} (F2).
 *
 * <p>The registry bean itself and its Kafka invalidation consumer are declared
 * in {@link TenantEntitlementRegistryR2dbc}; conditional-on-class on
 * {@code DatabaseClient} keeps the aspect optional for a (hypothetical) service
 * with no R2DBC on its classpath.
 */
@Configuration
@ConditionalOnClass(DatabaseClient.class)
public class EntitlementAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public EntitlementAspect entitlementAspect(TenantEntitlementRegistry registry) {
        return new EntitlementAspect(registry);
    }
}
