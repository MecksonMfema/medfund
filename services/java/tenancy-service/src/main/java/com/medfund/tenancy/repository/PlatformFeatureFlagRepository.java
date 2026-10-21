package com.medfund.tenancy.repository;

import com.medfund.tenancy.entity.PlatformFeatureFlag;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

public interface PlatformFeatureFlagRepository extends ReactiveCrudRepository<PlatformFeatureFlag, String> {
    Flux<PlatformFeatureFlag> findAllByOrderByKeyAsc();
}
