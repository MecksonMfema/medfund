package com.medfund.finance.producer.service;

import com.medfund.finance.producer.dto.CommissionAggregateRow;
import com.medfund.finance.producer.repository.CommissionAggregateQueryRepository;
import com.medfund.finance.producer.repository.CommissionAggregateQueryRepository.AggregateDimension;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Phase 18 K7 — thin adapter around
 * {@link CommissionAggregateQueryRepository} for the
 * /aggregate/commissions cross-service feed consumed by the executive KPI
 * composer.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommissionAggregateService {

    private final CommissionAggregateQueryRepository repository;

    public Mono<List<CommissionAggregateRow>> aggregatePaid(
            LocalDate periodStart,
            LocalDate periodEnd,
            AggregateDimension dimension,
            String insuranceLine,
            UUID producerId) {
        return repository.aggregatePaid(periodStart, periodEnd, dimension, insuranceLine, producerId)
                .collectList()
                .doOnNext(rows -> log.debug("commissions aggregate: {} rows for {}..{} dim={}",
                        rows.size(), periodStart, periodEnd, dimension));
    }
}
