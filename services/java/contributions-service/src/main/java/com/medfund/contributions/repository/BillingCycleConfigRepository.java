package com.medfund.contributions.repository;

import com.medfund.contributions.entity.BillingCycleConfig;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

public interface BillingCycleConfigRepository extends R2dbcRepository<BillingCycleConfig, UUID> {

    @Query("UPDATE billing_cycle_config SET last_committed_at = :ts, updated_at = :ts WHERE id = :id")
    Mono<Integer> updateLastCommittedAt(UUID id, Instant ts);
}
