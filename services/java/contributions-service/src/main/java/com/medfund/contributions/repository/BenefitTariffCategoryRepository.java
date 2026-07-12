package com.medfund.contributions.repository;

import com.medfund.contributions.entity.BenefitTariffCategory;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface BenefitTariffCategoryRepository extends R2dbcRepository<BenefitTariffCategory, UUID> {

    /** All category rows for one benefit — used by the form round-trip. */
    @Query("""
            SELECT * FROM benefit_tariff_categories
             WHERE scheme_benefit_id = :benefitId
             ORDER BY created_at
            """)
    Flux<BenefitTariffCategory> findByBenefit(UUID benefitId);

    /** Insert one row; ON CONFLICT DO NOTHING makes the batch idempotent
     *  under the (scheme_benefit_id, tariff_category_id) UNIQUE constraint. */
    @Modifying
    @Query("""
            INSERT INTO benefit_tariff_categories (scheme_benefit_id, tariff_category_id)
            VALUES (:benefitId, :categoryId)
            ON CONFLICT DO NOTHING
            """)
    Mono<Integer> link(UUID benefitId, UUID categoryId);

    /** Wipe all links for a benefit — first half of the delete-then-insert
     *  refresh used by the upsert path. */
    @Modifying
    @Query("DELETE FROM benefit_tariff_categories WHERE scheme_benefit_id = :benefitId")
    Mono<Integer> deleteByBenefit(UUID benefitId);
}
