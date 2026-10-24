package com.medfund.shared.entitlement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;
import reactor.kafka.receiver.ReceiverOptions;

import java.time.Duration;
import java.util.UUID;

/**
 * Default {@link TenantEntitlementRegistry} implementation. Reads the
 * {@code public.tenant_module_entitlements} table on cache miss and caches
 * each {@code (tenant, module)} entitlement for 30 seconds.
 *
 * <p>Mirrors {@code FlagRegistryR2dbc}, but threads the tenant through the
 * cache key, the SQL {@code WHERE}, and the invalidation. The cache is
 * process-local and Kafka-invalidated via {@link EntitlementInvalidationConsumer}
 * (topic {@code platform.module-entitlements.v1}); the 30s expiry is the
 * fallback for an event that never arrives.
 *
 * <p>The query runs under the caller's tenant role (the connection factory
 * does {@code SET ROLE <schema>_role}), so {@code public.tenant_module_entitlements}
 * must be granted {@code SELECT} to tenant roles — see tenancy-service V187,
 * which re-provisions {@code provision_tenant_role} with the new table.
 */
@Slf4j
@Configuration
public class TenantEntitlementRegistryR2dbc {

    @Bean
    @ConditionalOnMissingBean(TenantEntitlementRegistry.class)
    public TenantEntitlementRegistry tenantEntitlementRegistry(DatabaseClient client) {
        // Sized to tenants × modules rather than the flag registry's flat 64.
        Cache<Key, Boolean> cache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(30))
                .maximumSize(10_000)
                .build();
        return new CaffeineBackedRegistry(client, cache);
    }

    /**
     * Subscribes the local registry to tenancy-service's entitlement broadcast.
     * Conditional-on-bean keeps it optional, mirroring {@code FlagRegistryR2dbc}:
     * a service with no {@link ReceiverOptions} bean still gets a working
     * registry, just on TTL-only propagation.
     */
    @Bean
    @ConditionalOnBean(ReceiverOptions.class)
    @ConditionalOnMissingBean(EntitlementInvalidationConsumer.class)
    public EntitlementInvalidationConsumer entitlementInvalidationConsumer(
            ReceiverOptions<String, String> receiverOptions,
            TenantEntitlementRegistry registry,
            ObjectMapper objectMapper) {
        return new EntitlementInvalidationConsumer(receiverOptions, registry, objectMapper);
    }

    /** Composite cache key — entitlement is per (tenant, module). */
    record Key(UUID tenantId, ModuleKey module) {}

    static final class CaffeineBackedRegistry implements TenantEntitlementRegistry {
        private final DatabaseClient client;
        private final Cache<Key, Boolean> cache;

        CaffeineBackedRegistry(DatabaseClient client, Cache<Key, Boolean> cache) {
            this.client = client;
            this.cache = cache;
        }

        @Override
        public void invalidate(String tenantId, String rawKey) {
            UUID tid;
            ModuleKey module;
            try {
                tid = UUID.fromString(tenantId);
            } catch (IllegalArgumentException | NullPointerException e) {
                log.debug("Ignoring entitlement invalidation for non-UUID tenant {}", tenantId);
                return;
            }
            var parsed = ModuleKey.parse(rawKey);
            if (parsed.isEmpty()) {
                // Enum drift: tenancy-service knows a module this service's
                // build does not. Nothing cached under it, so nothing to do.
                log.debug("Ignoring entitlement invalidation for unknown module key {}", rawKey);
                return;
            }
            module = parsed.get();
            cache.invalidate(new Key(tid, module));
        }

        @Override
        public Mono<Boolean> isEntitled(UUID tenantId, ModuleKey module) {
            Key key = new Key(tenantId, module);
            Boolean cached = cache.getIfPresent(key);
            if (cached != null) return Mono.just(cached);
            return client.sql("SELECT enabled FROM public.tenant_module_entitlements "
                            + "WHERE tenant_id = :t AND module_key = :k")
                    .bind("t", tenantId)
                    .bind("k", module.name())
                    .map((row, meta) -> Boolean.TRUE.equals(row.get("enabled", Boolean.class)))
                    .one()
                    // Absent row = not entitled. Fail closed on uncertainty.
                    .defaultIfEmpty(Boolean.FALSE)
                    .doOnNext(v -> cache.put(key, v))
                    .onErrorResume(e -> {
                        log.warn("Entitlement lookup failed for tenant={} module={}: {}",
                                tenantId, module, e.toString());
                        return Mono.just(Boolean.FALSE);
                    });
        }
    }
}
