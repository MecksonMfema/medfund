package com.medfund.shared.flags;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;
import reactor.kafka.receiver.ReceiverOptions;

import java.time.Duration;

/**
 * Default {@link FlagRegistry} implementation. Reads the
 * {@code public.platform_feature_flags} table on cache miss and caches
 * each flag's enabled state for 30 seconds.
 *
 * <p>The cache is process-local, but no longer waits out its TTL: this
 * configuration also wires {@link FlagInvalidationConsumer}, which drops the
 * cached entry as soon as tenancy-service broadcasts a toggle on
 * {@code platform.feature-flags.v1}. The 30s expiry is the fallback for an
 * event that never arrives (broker outage, consumer restart mid-toggle).
 */
@Slf4j
@Configuration
public class FlagRegistryR2dbc {

    @Bean
    @ConditionalOnMissingBean(FlagRegistry.class)
    public FlagRegistry flagRegistry(DatabaseClient client) {
        Cache<PlatformFlag, Boolean> cache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(30))
                .maximumSize(64)
                .build();
        return new CaffeineBackedRegistry(client, cache);
    }

    /**
     * Subscribes the local registry to tenancy-service's flag broadcast.
     * Conditional-on-bean keeps it optional, mirroring
     * {@code PermissionsAutoConfiguration}: a service with no
     * {@link ReceiverOptions} bean (no Kafka consumer config) still gets a
     * working registry, just on TTL-only propagation.
     */
    @Bean
    @ConditionalOnBean(ReceiverOptions.class)
    @ConditionalOnMissingBean(FlagInvalidationConsumer.class)
    public FlagInvalidationConsumer flagInvalidationConsumer(
            ReceiverOptions<String, String> receiverOptions,
            FlagRegistry registry,
            ObjectMapper objectMapper) {
        return new FlagInvalidationConsumer(receiverOptions, registry, objectMapper);
    }

    static final class CaffeineBackedRegistry implements FlagRegistry {
        private final DatabaseClient client;
        private final Cache<PlatformFlag, Boolean> cache;

        CaffeineBackedRegistry(DatabaseClient client, Cache<PlatformFlag, Boolean> cache) {
            this.client = client;
            this.cache = cache;
        }

        @Override
        public void invalidate(String rawKey) {
            PlatformFlag flag;
            try {
                flag = PlatformFlag.valueOf(rawKey);
            } catch (IllegalArgumentException e) {
                // Enum drift: tenancy-service knows a flag this service's
                // build does not. Nothing cached under it, so nothing to do.
                log.debug("Ignoring invalidation for unknown flag key {}", rawKey);
                return;
            }
            cache.invalidate(flag);
        }

        @Override
        public Mono<Boolean> isEnabled(PlatformFlag flag) {
            Boolean cached = cache.getIfPresent(flag);
            if (cached != null) return Mono.just(cached);
            return client.sql("SELECT enabled FROM public.platform_feature_flags WHERE key = :k")
                    .bind("k", flag.name())
                    .map((row, meta) -> {
                        Boolean v = row.get("enabled", Boolean.class);
                        return v != null ? v : Boolean.FALSE;
                    })
                    .one()
                    .defaultIfEmpty(Boolean.FALSE)
                    .doOnNext(v -> cache.put(flag, v))
                    .onErrorResume(e -> {
                        log.warn("Flag lookup failed for {}: {}", flag, e.toString());
                        return Mono.just(Boolean.FALSE);
                    });
        }
    }
}
