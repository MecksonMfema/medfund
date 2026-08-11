package com.medfund.contributions.repository;

import com.medfund.contributions.entity.BenefitCostShareTier;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface BenefitCostShareTierRepository extends R2dbcRepository<BenefitCostShareTier, UUID> {

    @Query("""
            SELECT * FROM benefit_cost_share_tier
             WHERE benefit_cost_share_id = :benefitCostShareId
             ORDER BY tier_name
           """)
    Flux<BenefitCostShareTier> findByBenefitCostShareId(UUID benefitCostShareId);
}
