package com.medfund.finance.repository;

import com.medfund.finance.entity.AdvancePaymentApplication;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface AdvancePaymentApplicationRepository
        extends R2dbcRepository<AdvancePaymentApplication, UUID> {

    @Query("""
        SELECT * FROM advance_payment_applications
         WHERE advance_payment_id = :advanceId
         ORDER BY applied_at DESC
        """)
    Flux<AdvancePaymentApplication> findByAdvancePaymentId(UUID advanceId);

    @Query("""
        SELECT * FROM advance_payment_applications
         WHERE payment_id = :paymentId
         ORDER BY applied_at DESC
        """)
    Flux<AdvancePaymentApplication> findByPaymentId(UUID paymentId);

    @Query("""
        SELECT * FROM advance_payment_applications
         WHERE payment_run_id = :runId
         ORDER BY applied_at DESC
        """)
    Flux<AdvancePaymentApplication> findByPaymentRunId(UUID runId);
}
