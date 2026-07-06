package com.medfund.contributions.repository;

import com.medfund.contributions.entity.CurrencyChangeWaitingPeriodRule;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface CurrencyChangeWaitingPeriodRuleRepository
        extends R2dbcRepository<CurrencyChangeWaitingPeriodRule, UUID> {

    /**
     * Look up the rule for a specific (from → to) currency swap.
     * Both codes are compared case-insensitively — callers uppercase
     * before binding but the query is defensive too.
     */
    @Query("""
            SELECT * FROM currency_change_waiting_period_rules
             WHERE UPPER(from_currency) = UPPER(:fromCurrency)
               AND UPPER(to_currency)   = UPPER(:toCurrency)
               AND is_active = TRUE
             LIMIT 1
            """)
    Mono<CurrencyChangeWaitingPeriodRule> findActive(String fromCurrency, String toCurrency);
}
