package com.medfund.shared.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.kafka.receiver.ReceiverOptions;

/**
 * Auto-wires the permission-resolution stack — {@link DefaultPermissionResolver},
 * {@link PermissionResolverFilter}, and {@link PermissionAspect} — into every
 * service that depends on the shared module.
 *
 * <p>Conditional-on-class keeps the wiring optional: a service that doesn't
 * have R2DBC on its classpath (none today, but the door's open) silently
 * skips the resolver. Conditional-on-missing-bean lets a service substitute
 * its own {@link PermissionResolver} implementation without disabling the
 * filter or aspect.
 */
@Configuration
@ConditionalOnClass(DatabaseClient.class)
public class PermissionsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public PermissionResolver permissionResolver(DatabaseClient db) {
        return new DefaultPermissionResolver(db);
    }

    @Bean
    @ConditionalOnMissingBean
    public PermissionResolverFilter permissionResolverFilter(PermissionResolver resolver) {
        return new PermissionResolverFilter(resolver);
    }

    @Bean
    @ConditionalOnMissingBean
    public PermissionAspect permissionAspect() {
        return new PermissionAspect();
    }

    /**
     * Wires the Kafka consumer that drops cached permissions when a remote
     * service publishes an invalidation event. Conditional-on-bean keeps it
     * optional — services without Kafka receive infrastructure (no
     * {@code ReceiverOptions} bean) silently skip the consumer.
     */
    @Bean
    @ConditionalOnBean(ReceiverOptions.class)
    @ConditionalOnMissingBean
    public PermissionInvalidationConsumer permissionInvalidationConsumer(
            ReceiverOptions<String, String> receiverOptions,
            PermissionResolver resolver,
            ObjectMapper objectMapper) {
        return new PermissionInvalidationConsumer(receiverOptions, resolver, objectMapper);
    }
}
