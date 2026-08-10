package com.medfund.finance.repository;

import com.medfund.finance.entity.Adjustment;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface AdjustmentRepository extends R2dbcRepository<Adjustment, UUID> {

    @Query("SELECT * FROM adjustments WHERE provider_id = :providerId ORDER BY created_at DESC")
    Flux<Adjustment> findByProviderId(UUID providerId);

    @Query("SELECT * FROM adjustments WHERE member_id = :memberId ORDER BY created_at DESC")
    Flux<Adjustment> findByMemberId(UUID memberId);

    @Query("SELECT * FROM adjustments WHERE status = :status ORDER BY created_at DESC")
    Flux<Adjustment> findByStatus(String status);

    @Query("SELECT * FROM adjustments ORDER BY created_at DESC")
    Flux<Adjustment> findAllOrderByCreatedAtDesc();

    @Query("SELECT EXISTS(SELECT 1 FROM adjustments WHERE adjustment_number = :adjustmentNumber)")
    Mono<Boolean> existsByAdjustmentNumber(String adjustmentNumber);
}
