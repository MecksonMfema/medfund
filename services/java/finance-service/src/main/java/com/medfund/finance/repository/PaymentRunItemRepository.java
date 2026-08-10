package com.medfund.finance.repository;

import com.medfund.finance.entity.PaymentRunItem;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface PaymentRunItemRepository extends R2dbcRepository<PaymentRunItem, UUID> {

    @Query("SELECT * FROM payment_run_items WHERE payment_run_id = :paymentRunId ORDER BY created_at")
    Flux<PaymentRunItem> findByPaymentRunId(UUID paymentRunId);

    @Query("SELECT * FROM payment_run_items WHERE provider_id = :providerId")
    Flux<PaymentRunItem> findByProviderId(UUID providerId);

    @Query("SELECT * FROM payment_run_items WHERE member_id = :memberId")
    Flux<PaymentRunItem> findByMemberId(UUID memberId);

    @Query("SELECT * FROM payment_run_items WHERE payment_id = :paymentId LIMIT 1")
    reactor.core.publisher.Mono<PaymentRunItem> findByPaymentId(UUID paymentId);
}
