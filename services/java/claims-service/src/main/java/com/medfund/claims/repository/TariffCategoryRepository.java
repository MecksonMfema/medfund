package com.medfund.claims.repository;

import com.medfund.claims.entity.TariffCategory;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface TariffCategoryRepository extends R2dbcRepository<TariffCategory, UUID> {

    @Query("SELECT * FROM tariff_categories ORDER BY sort_order, label")
    Flux<TariffCategory> findAllOrderBySortOrder();

    @Query("SELECT * FROM tariff_categories WHERE is_active = TRUE ORDER BY sort_order, label")
    Flux<TariffCategory> findAllActiveOrderBySortOrder();

    @Query("SELECT * FROM tariff_categories WHERE LOWER(code) = LOWER(:code) LIMIT 1")
    Mono<TariffCategory> findByCode(String code);
}
