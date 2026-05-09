package com.medfund.contributions.repository;

import com.medfund.contributions.entity.SchemeChangeWaitingPeriodRule;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface SchemeChangeWaitingPeriodRuleRepository
        extends R2dbcRepository<SchemeChangeWaitingPeriodRule, UUID> {

    @Query("""
        SELECT * FROM scheme_change_waiting_period_rules
         ORDER BY change_type, benefit_type NULLS FIRST
        """)
    Flux<SchemeChangeWaitingPeriodRule> findAllOrdered();
}
