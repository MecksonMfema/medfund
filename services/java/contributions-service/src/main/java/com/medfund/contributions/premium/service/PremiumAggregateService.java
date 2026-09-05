package com.medfund.contributions.premium.service;

import com.medfund.contributions.premium.dto.PremiumEarnedAggregateRow;
import com.medfund.contributions.premium.repository.PremiumAggregateQueryRepository;
import com.medfund.contributions.premium.repository.PremiumAggregateQueryRepository.Dimension;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Phase 18 K8 — thin adapter around {@link PremiumAggregateQueryRepository}
 * for the /aggregate/premium-earned cross-service feed. Rows stay
 * native-currency; the KPI composer performs any reporting-currency
 * conversion downstream.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PremiumAggregateService {

    private final PremiumAggregateQueryRepository queryRepository;

    public Mono<List<PremiumEarnedAggregateRow>> earnedPremium(
            LocalDate periodStart,
            LocalDate periodEnd,
            Dimension dimension,
            String insuranceLine,
            UUID schemeId) {
        return queryRepository.earnedPremium(periodStart, periodEnd, dimension, insuranceLine, schemeId)
                .collectList()
                .doOnNext(rows -> log.debug("earned-premium aggregate: {} rows for {}..{} dim={}",
                        rows.size(), periodStart, periodEnd, dimension));
    }
}
