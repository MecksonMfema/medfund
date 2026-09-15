package com.medfund.tenancy.config;

import com.medfund.shared.flags.PlatformFlag;
import com.medfund.tenancy.entity.PlatformFeatureFlag;
import com.medfund.tenancy.repository.PlatformFeatureFlagRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;

/**
 * Ensures a row exists in {@code public.platform_feature_flags} for every
 * value of {@link PlatformFlag}. Rows are inserted with {@code enabled=false}
 * so a new flag defaults to off; an admin has to explicitly turn it on via
 * {@code PUT /api/v1/platform/feature-flags/{key}}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlatformFlagSeeder implements CommandLineRunner {

    private final PlatformFeatureFlagRepository repo;

    @Override
    public void run(String... args) {
        // blockLast() so startup doesn't proceed (and no request handling
        // begins) until every catalogue row exists. Missing rows here mean
        // PlatformFeatureFlagService.update() would 500 on the first toggle.
        Flux.fromArray(PlatformFlag.values())
                .flatMap(flag -> repo.findById(flag.name())
                        .switchIfEmpty(Mono.defer(() -> {
                            PlatformFeatureFlag row = new PlatformFeatureFlag();
                            row.setKey(flag.name());
                            row.setEnabled(false);
                            row.setUpdatedAt(OffsetDateTime.now());
                            row.setUpdatedBy("system");
                            row.setVersion(0L);
                            row.markAsNew(); // force R2DBC to INSERT, not UPDATE
                            log.info("Seeding new platform feature flag: {}", flag.name());
                            return repo.save(row);
                        })))
                .doOnNext(row -> log.debug("Flag: {} enabled={}", row.getKey(), row.getEnabled()))
                .doOnError(e -> log.error("PlatformFlagSeeder failed", e))
                .blockLast();
    }
}
