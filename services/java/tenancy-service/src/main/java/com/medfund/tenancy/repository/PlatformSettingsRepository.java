package com.medfund.tenancy.repository;

import com.medfund.tenancy.entity.PlatformSettings;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface PlatformSettingsRepository extends ReactiveCrudRepository<PlatformSettings, UUID> {
    Mono<PlatformSettings> findFirstBySingletonIsTrue();
}
