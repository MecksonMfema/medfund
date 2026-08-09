package com.medfund.finance.repository;

import com.medfund.finance.entity.AdvancePayment;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface AdvancePaymentRepository extends R2dbcRepository<AdvancePayment, UUID> {

    @Query("SELECT * FROM advance_payments ORDER BY recorded_at DESC")
    Flux<AdvancePayment> findAllOrdered();

    @Query("SELECT * FROM advance_payments WHERE provider_id = :providerId ORDER BY recorded_at DESC")
    Flux<AdvancePayment> findByProviderId(UUID providerId);

    @Query("SELECT * FROM advance_payments WHERE member_id = :memberId ORDER BY recorded_at DESC")
    Flux<AdvancePayment> findByMemberId(UUID memberId);

    /**
     * Oldest ADVANCE row for a provider/currency that still has outstanding
     * balance (status in approved | applied). Used by PaymentRunService when
     * writing application rows — we consume the oldest advance first (FIFO)
     * so tenants can reason about ordering.
     */
    @Query("""
        SELECT ap.*
          FROM advance_payments ap
         WHERE ap.provider_id = :providerId
           AND ap.currency_code = :currencyCode
           AND ap.type = 'ADVANCE'
           AND ap.status IN ('approved', 'applied')
         ORDER BY ap.recorded_at ASC, ap.id ASC
         LIMIT 1
        """)
    Mono<AdvancePayment> findOldestOpenForProvider(UUID providerId, String currencyCode);

    @Query("""
        SELECT ap.*
          FROM advance_payments ap
         WHERE ap.member_id = :memberId
           AND ap.currency_code = :currencyCode
           AND ap.type = 'ADVANCE'
           AND ap.status IN ('approved', 'applied')
         ORDER BY ap.recorded_at ASC, ap.id ASC
         LIMIT 1
        """)
    Mono<AdvancePayment> findOldestOpenForMember(UUID memberId, String currencyCode);
}
