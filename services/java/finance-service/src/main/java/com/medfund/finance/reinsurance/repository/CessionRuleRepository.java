package com.medfund.finance.reinsurance.repository;

import com.medfund.finance.reinsurance.entity.CessionRule;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface CessionRuleRepository extends R2dbcRepository<CessionRule, UUID> {

    @Query("SELECT * FROM cession_rule WHERE treaty_id = :treatyId ORDER BY created_at")
    Flux<CessionRule> findByTreatyId(UUID treatyId);

    @Query("SELECT * FROM cession_rule WHERE treaty_id = :treatyId AND enabled = TRUE ORDER BY created_at")
    Flux<CessionRule> findByTreatyIdAndEnabledTrue(UUID treatyId);

    @Query("SELECT * FROM cession_rule WHERE treaty_id = :treatyId AND rule_definition_id = :ruleDefinitionId")
    Mono<CessionRule> findByTreatyIdAndRuleDefinitionId(UUID treatyId, UUID ruleDefinitionId);
}
