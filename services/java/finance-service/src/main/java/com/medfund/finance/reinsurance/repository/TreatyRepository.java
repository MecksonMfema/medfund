package com.medfund.finance.reinsurance.repository;

import com.medfund.finance.reinsurance.entity.Treaty;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface TreatyRepository extends R2dbcRepository<Treaty, UUID> {

    @Query("SELECT * FROM treaty ORDER BY inception_date DESC, treaty_ref ASC OFFSET :offset LIMIT :limit")
    Flux<Treaty> findPage(int offset, int limit);

    @Query("SELECT * FROM treaty WHERE status = :status ORDER BY inception_date DESC OFFSET :offset LIMIT :limit")
    Flux<Treaty> findPageByStatus(String status, int offset, int limit);

    @Query("SELECT COUNT(*) FROM treaty")
    Mono<Long> countAll();

    @Query("SELECT COUNT(*) FROM treaty WHERE status = :status")
    Mono<Long> countByStatus(String status);

    @Query("SELECT * FROM treaty WHERE treaty_ref = :treatyRef")
    Mono<Treaty> findByTreatyRef(String treatyRef);

    /**
     * ACTIVE treaties whose applicable_lines include the given insurance line
     * and whose window covers today. Ordered to give a stable cession sequence.
     */
    @Query("""
        SELECT t.* FROM treaty t
        JOIN treaty_applicable_line l ON l.treaty_id = t.id
        WHERE t.status = 'ACTIVE'
          AND l.insurance_line = :insuranceLine
          AND t.inception_date <= CURRENT_DATE
          AND t.expiry_date    >  CURRENT_DATE
        ORDER BY t.inception_date, t.treaty_ref
        """)
    Flux<Treaty> findActiveByInsuranceLine(String insuranceLine);

    /**
     * ACTIVE non-proportional treaties (XoL / StopLoss) that carry a
     * positive {@code expected_annual_premium}. Used by
     * {@code ReinsuranceTreatyPremiumExecutor} to write a flat PREMIUM
     * cession per treaty at inception — proportional (QS / SS) treaties
     * take their premium per contribution via
     * {@code ReinsurancePremiumCessionConsumer}, so they are excluded here.
     */
    @Query("""
        SELECT * FROM treaty
         WHERE status = 'ACTIVE'
           AND treaty_type IN ('EXCESS_OF_LOSS', 'STOP_LOSS')
           AND expected_annual_premium IS NOT NULL
           AND expected_annual_premium > 0
         ORDER BY inception_date, treaty_ref
        """)
    Flux<Treaty> findActiveNonProportionalWithExpectedPremium();
}
