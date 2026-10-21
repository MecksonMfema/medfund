package com.medfund.user.repository;

import com.medfund.user.entity.VariableFeeSchedule;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.UUID;

public interface VariableFeeScheduleRepository extends R2dbcRepository<VariableFeeSchedule, UUID> {

    @Query("""
            SELECT * FROM variable_fee_schedule
            WHERE fund_id = :fundId
            ORDER BY effective_from DESC
            """)
    Flux<VariableFeeSchedule> findByFundIdOrderByEffectiveFromDesc(UUID fundId);

    /**
     * Fee percentage in effect on {@code asOf}. §16 VFA reads this per
     * reporting period.
     */
    @Query("""
            SELECT * FROM variable_fee_schedule
            WHERE fund_id = :fundId
              AND effective_from <= :asOf
              AND (effective_to IS NULL OR effective_to > :asOf)
            ORDER BY effective_from DESC
            LIMIT 1
            """)
    Mono<VariableFeeSchedule> findEffectiveOn(UUID fundId, LocalDate asOf);
}
