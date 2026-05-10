package com.medfund.finance.repository;

import com.medfund.finance.entity.CtcPayment;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface CtcPaymentRepository extends R2dbcRepository<CtcPayment, UUID> {

    @Query("SELECT * FROM ctc_payments ORDER BY created_at DESC")
    Flux<CtcPayment> findAllOrdered();

    @Query("SELECT * FROM ctc_payments WHERE committed = :committed ORDER BY created_at DESC")
    Flux<CtcPayment> findByCommitted(Boolean committed);

    @Query("SELECT * FROM ctc_payments WHERE group_id = :groupId ORDER BY created_at DESC")
    Flux<CtcPayment> findByGroupId(UUID groupId);
}
