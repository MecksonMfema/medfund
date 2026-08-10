package com.medfund.finance.repository;

import com.medfund.finance.entity.PaymentAdviceLine;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface PaymentAdviceLineRepository extends R2dbcRepository<PaymentAdviceLine, UUID> {

    @Query("SELECT * FROM payment_advice_lines WHERE payment_advice_id = :paymentAdviceId ORDER BY sequence")
    Flux<PaymentAdviceLine> findByPaymentAdviceIdOrderBySequence(UUID paymentAdviceId);

    @Query("DELETE FROM payment_advice_lines WHERE payment_advice_id = :paymentAdviceId")
    Mono<Void> deleteByPaymentAdviceId(UUID paymentAdviceId);
}
