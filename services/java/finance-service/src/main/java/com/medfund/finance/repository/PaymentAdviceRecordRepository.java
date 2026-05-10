package com.medfund.finance.repository;

import com.medfund.finance.entity.PaymentAdviceRecord;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface PaymentAdviceRecordRepository extends R2dbcRepository<PaymentAdviceRecord, UUID> {

    @Query("SELECT * FROM payment_advices ORDER BY issued_at DESC")
    Flux<PaymentAdviceRecord> findAllOrdered();

    @Query("SELECT * FROM payment_advices WHERE payment_run_id = :paymentRunId ORDER BY issued_at DESC")
    Flux<PaymentAdviceRecord> findByPaymentRunId(UUID paymentRunId);

    @Query("SELECT * FROM payment_advices WHERE provider_id = :providerId ORDER BY issued_at DESC")
    Flux<PaymentAdviceRecord> findByProviderId(UUID providerId);
}
