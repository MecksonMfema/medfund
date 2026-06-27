package com.medfund.shared.tenant;

import io.r2dbc.spi.ConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-wires {@link TenantAwareConnectionFactory} around the Spring Boot
 * auto-configured R2DBC {@code ConnectionFactory}. Every service that
 * component-scans {@code com.medfund.shared} (all of them) picks this up.
 *
 * <p>The wrap is done via a {@link BeanPostProcessor} so we don't fight
 * Spring Boot's auto-configuration of the {@code ConnectionPool}: the pool
 * is still built normally, and our decorator runs <em>around</em> each
 * {@code create()} from the pool, applying {@code RESET ROLE → SET
 * search_path → SET ROLE} based on the per-request tenant context.
 *
 * <p>Wrap is idempotent — the post-processor returns the bean unchanged if
 * it's already a {@link TenantAwareConnectionFactory}, so re-running through
 * the post-processor chain (which Spring may do for proxies) is safe.
 *
 * <p>Without this wiring the decorator class is dead code: every R2DBC
 * connection acquires with the default {@code search_path = public}, so
 * unqualified queries silently target the platform schema instead of the
 * caller's tenant schema. This was the cause of the 2026-06-27 incident
 * where rows migrated into {@code tenant_<slug>.<table>} became invisible
 * to the application — see {@link TenantAwareConnectionFactory} javadoc.
 */
@Configuration
@ConditionalOnClass(ConnectionFactory.class)
public class TenantAwareAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(TenantAwareAutoConfiguration.class);

    @Bean
    public static BeanPostProcessor tenantAwareConnectionFactoryPostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof ConnectionFactory cf && !(bean instanceof TenantAwareConnectionFactory)) {
                    log.info("Wrapping ConnectionFactory bean '{}' ({}) with TenantAwareConnectionFactory",
                            beanName, bean.getClass().getName());
                    return new TenantAwareConnectionFactory(cf);
                }
                return bean;
            }
        };
    }
}
