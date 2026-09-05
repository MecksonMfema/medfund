package com.medfund.claims.service;

import com.medfund.claims.dto.ClaimsIncurredAggregateRow;
import com.medfund.claims.repository.ClaimsAggregateQueryRepository;
import com.medfund.claims.repository.ClaimsAggregateQueryRepository.Dimension;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Phase 18 K9 — thin adapter around {@link ClaimsAggregateQueryRepository}
 * for the /aggregate/claims-incurred cross-service feed consumed by the
 * executive KPI composer. Rows stay native-currency; the composer performs
 * any reporting-currency conversion downstream via FxRateReader.convert.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClaimsAggregateService {

    private final ClaimsAggregateQueryRepository queryRepository;

    public Mono<List<ClaimsIncurredAggregateRow>> claimsIncurred(
            LocalDate periodStart,
            LocalDate periodEnd,
            Dimension dimension,
            String insuranceLine,
            UUID schemeId) {
        return queryRepository.claimsIncurred(periodStart, periodEnd, dimension, insuranceLine, schemeId)
                .collectList()
                .doOnNext(rows -> log.debug("claims-incurred aggregate: {} rows for {}..{} dim={}",
                        rows.size(), periodStart, periodEnd, dimension));
    }
}
