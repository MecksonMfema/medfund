package com.medfund.tenancy.repository;

import com.medfund.tenancy.entity.Currency;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface CurrencyRepository extends R2dbcRepository<Currency, String> {

    @Query("SELECT * FROM public.currencies WHERE is_active = true ORDER BY code")
    Flux<Currency> findAllActive();

    @Query("SELECT EXISTS(SELECT 1 FROM public.currencies WHERE code = :code AND is_active = true)")
    Mono<Boolean> existsActiveByCode(String code);
}
