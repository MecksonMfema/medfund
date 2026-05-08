package com.medfund.tenancy.repository;

import com.medfund.tenancy.entity.ExchangeRate;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.UUID;

public interface ExchangeRateRepository extends R2dbcRepository<ExchangeRate, UUID> {

    /**
     * Find the most recent rate at or before {@code asOf} for the given pair
     * and tenant. {@code tenantId} is matched permissively — a tenant-specific
     * rate wins over a platform-wide rate, so callers should query both.
     */
    @Query("""
        SELECT * FROM public.exchange_rates
         WHERE base_currency  = :base
           AND quote_currency = :quote
           AND rate_date     <= :asOf
           AND (tenant_id    = :tenantId OR tenant_id IS NULL)
         ORDER BY tenant_id NULLS LAST, rate_date DESC, created_at DESC
         LIMIT 1
        """)
    Mono<ExchangeRate> findLatest(String base, String quote, LocalDate asOf, UUID tenantId);

    @Query("""
        SELECT * FROM public.exchange_rates
         WHERE base_currency  = :base
           AND quote_currency = :quote
           AND rate_date     >= :from
           AND rate_date     <= :to
           AND (tenant_id    = :tenantId OR tenant_id IS NULL)
         ORDER BY rate_date DESC, created_at DESC
        """)
    Flux<ExchangeRate> findHistory(String base, String quote, LocalDate from, LocalDate to, UUID tenantId);
}
