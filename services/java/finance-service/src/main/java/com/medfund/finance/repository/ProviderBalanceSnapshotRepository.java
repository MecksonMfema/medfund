package com.medfund.finance.repository;

import com.medfund.finance.entity.ProviderBalanceSnapshot;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface ProviderBalanceSnapshotRepository extends R2dbcRepository<ProviderBalanceSnapshot, UUID> {

    @Query("SELECT * FROM provider_balance_snapshot WHERE provider_id = :providerId ORDER BY taken_at DESC, payment_run_id")
    Flux<ProviderBalanceSnapshot> findByProviderId(UUID providerId);

    @Query("SELECT * FROM provider_balance_snapshot WHERE provider_id = :providerId AND payment_run_id = :runId")
    Mono<ProviderBalanceSnapshot> findByProviderIdAndPaymentRunId(UUID providerId, UUID runId);
}
