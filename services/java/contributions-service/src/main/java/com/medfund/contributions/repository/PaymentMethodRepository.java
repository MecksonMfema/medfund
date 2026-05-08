package com.medfund.contributions.repository;

import com.medfund.contributions.entity.PaymentMethod;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface PaymentMethodRepository extends R2dbcRepository<PaymentMethod, UUID> {

    @Query("SELECT * FROM payment_methods ORDER BY label")
    Flux<PaymentMethod> findAllOrdered();

    @Query("SELECT * FROM payment_methods WHERE is_active = true ORDER BY label")
    Flux<PaymentMethod> findAllActiveOrdered();

    Mono<PaymentMethod> findByCode(String code);
}
