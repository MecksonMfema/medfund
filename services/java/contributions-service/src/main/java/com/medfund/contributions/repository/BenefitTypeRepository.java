package com.medfund.contributions.repository;

import com.medfund.contributions.entity.BenefitType;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface BenefitTypeRepository extends R2dbcRepository<BenefitType, UUID> {

    @Query("SELECT * FROM benefit_types ORDER BY sort_order, label")
    Flux<BenefitType> findAllOrdered();

    @Query("SELECT * FROM benefit_types WHERE is_active = true ORDER BY sort_order, label")
    Flux<BenefitType> findAllActiveOrdered();

    Mono<BenefitType> findByCode(String code);
}
