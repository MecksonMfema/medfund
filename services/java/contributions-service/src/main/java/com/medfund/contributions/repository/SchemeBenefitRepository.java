package com.medfund.contributions.repository;

import com.medfund.contributions.entity.SchemeBenefit;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.UUID;

public interface SchemeBenefitRepository extends R2dbcRepository<SchemeBenefit, UUID> {

    @Query("SELECT * FROM scheme_benefits WHERE scheme_id = :schemeId ORDER BY name")
    Flux<SchemeBenefit> findBySchemeId(UUID schemeId);

    /**
     * Sum of active benefit annual limits for a scheme, restricted to a
     * currency so mixed-currency rows on the same scheme (rare, but possible)
     * cannot contaminate the total. Used by
     * {@code SchemeChangeService.classifyByCurrency} to distinguish UPGRADE
     * from DOWNGRADE when the two schemes share a currency.
     */
    @Query("""
            SELECT COALESCE(SUM(annual_limit), 0)
              FROM scheme_benefits
             WHERE scheme_id = :schemeId
               AND currency_code = :currencyCode
               AND status = 'active'
            """)
    Mono<BigDecimal> sumAnnualLimit(UUID schemeId, String currencyCode);
}
