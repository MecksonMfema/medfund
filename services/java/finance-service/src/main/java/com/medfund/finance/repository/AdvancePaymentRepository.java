package com.medfund.finance.repository;

import com.medfund.finance.entity.AdvancePayment;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface AdvancePaymentRepository extends R2dbcRepository<AdvancePayment, UUID> {

    @Query("SELECT * FROM advance_payments ORDER BY recorded_at DESC")
    Flux<AdvancePayment> findAllOrdered();

    @Query("SELECT * FROM advance_payments WHERE provider_id = :providerId ORDER BY recorded_at DESC")
    Flux<AdvancePayment> findByProviderId(UUID providerId);

    @Query("SELECT * FROM advance_payments WHERE member_id = :memberId ORDER BY recorded_at DESC")
    Flux<AdvancePayment> findByMemberId(UUID memberId);
}
