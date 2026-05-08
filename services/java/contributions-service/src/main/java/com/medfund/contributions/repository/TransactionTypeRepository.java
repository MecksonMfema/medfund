package com.medfund.contributions.repository;

import com.medfund.contributions.entity.TransactionType;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface TransactionTypeRepository extends R2dbcRepository<TransactionType, UUID> {

    @Query("SELECT * FROM transaction_types ORDER BY label")
    Flux<TransactionType> findAllOrdered();

    @Query("SELECT * FROM transaction_types WHERE is_active = true ORDER BY label")
    Flux<TransactionType> findAllActiveOrdered();

    Mono<TransactionType> findByCode(String code);
}
