package com.medfund.shared.flags;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Default {@link FlagRegistry} implementation. Reads the
 * {@code public.platform_feature_flags} table on cache miss and caches
 * each flag's enabled state for 30 seconds. Cache is process-local; no
 * cross-node invalidation — a 30s propagation delay is acceptable for
 * pilot readiness.
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

    static final class CaffeineBackedRegistry implements FlagRegistry {
        private final DatabaseClient client;
        private final Cache<PlatformFlag, Boolean> cache;

        CaffeineBackedRegistry(DatabaseClient client, Cache<PlatformFlag, Boolean> cache) {
            this.client = client;
            this.cache = cache;
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
