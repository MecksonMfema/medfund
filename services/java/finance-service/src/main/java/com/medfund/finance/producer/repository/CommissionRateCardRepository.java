package com.medfund.finance.producer.repository;

import com.medfund.finance.producer.entity.CommissionRateCard;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.UUID;

public interface CommissionRateCardRepository extends R2dbcRepository<CommissionRateCard, UUID> {

    /**
     * Pick the most specific applicable card for the given line + tier + as-of
     * date. A tier-scoped card wins over a tier-agnostic ({@code producer_tier
     * IS NULL}) fallback via {@code ORDER BY producer_tier NULLS LAST}.
     */
    @Query("""
            SELECT * FROM commission_rate_card
             WHERE insurance_line = :insuranceLine
               AND (producer_tier = :tier OR producer_tier IS NULL)
               AND effective_from <= :asOf
               AND (effective_to IS NULL OR effective_to >= :asOf)
               AND is_active = TRUE
             ORDER BY producer_tier NULLS LAST, effective_from DESC
             LIMIT 1
            """)
    Mono<CommissionRateCard> findApplicable(String insuranceLine, String tier, LocalDate asOf);

    @Query("SELECT * FROM commission_rate_card WHERE is_active = TRUE ORDER BY effective_from DESC")
    Flux<CommissionRateCard> findActiveOrderByEffectiveFromDesc();

    @Query("SELECT * FROM commission_rate_card ORDER BY effective_from DESC OFFSET :offset LIMIT :limit")
    Flux<CommissionRateCard> findPage(int offset, int limit);

    @Query("SELECT * FROM commission_rate_card WHERE is_active = :active ORDER BY effective_from DESC OFFSET :offset LIMIT :limit")
    Flux<CommissionRateCard> findPageByActive(boolean active, int offset, int limit);

    @Query("SELECT COUNT(*) FROM commission_rate_card")
    Mono<Long> countAll();

    @Query("SELECT COUNT(*) FROM commission_rate_card WHERE is_active = :active")
    Mono<Long> countByActive(boolean active);
}
