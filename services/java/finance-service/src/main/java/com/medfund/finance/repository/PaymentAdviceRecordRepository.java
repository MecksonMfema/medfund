package com.medfund.finance.repository;

import com.medfund.finance.entity.PaymentAdviceRecord;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface PaymentAdviceRecordRepository extends R2dbcRepository<PaymentAdviceRecord, UUID> {

    @Query("SELECT * FROM payment_advices ORDER BY issued_at DESC")
    Flux<PaymentAdviceRecord> findAllOrdered();

    @Query("SELECT * FROM payment_advices WHERE payment_run_id = :paymentRunId ORDER BY issued_at DESC")
    Flux<PaymentAdviceRecord> findByPaymentRunId(UUID paymentRunId);

    @Query("SELECT * FROM payment_advices WHERE provider_id = :providerId ORDER BY issued_at DESC")
    Flux<PaymentAdviceRecord> findByProviderId(UUID providerId);

    @Query("SELECT * FROM payment_advices WHERE member_id = :memberId ORDER BY issued_at DESC")
    Flux<PaymentAdviceRecord> findByMemberId(UUID memberId);

    @Query("SELECT * FROM payment_advices WHERE payment_run_id = :paymentRunId AND provider_id = :providerId")
    Mono<PaymentAdviceRecord> findByPaymentRunIdAndProviderId(UUID paymentRunId, UUID providerId);

    @Query("SELECT * FROM payment_advices WHERE payment_run_id = :paymentRunId AND member_id = :memberId")
    Mono<PaymentAdviceRecord> findByPaymentRunIdAndMemberId(UUID paymentRunId, UUID memberId);

    @Query("DELETE FROM payment_advices WHERE payment_run_id = :paymentRunId")
    Mono<Void> deleteByPaymentRunId(UUID paymentRunId);
}
