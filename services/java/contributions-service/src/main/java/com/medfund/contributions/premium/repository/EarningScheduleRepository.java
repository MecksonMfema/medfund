package com.medfund.contributions.premium.repository;

import com.medfund.contributions.premium.entity.EarningSchedule;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.UUID;

public interface EarningScheduleRepository extends ReactiveCrudRepository<EarningSchedule, UUID> {

    Flux<EarningSchedule> findByPolicyIdAndPolicySource(UUID policyId, String policySource);

    /**
     * Period-close scan for {@code PremiumEarningExecutor}: rows whose period
     * has closed but which still carry {@code earned_at_period_end IS NULL}.
     * The {@code ix_earning_schedule_unclosed} partial index covers this.
     */
    Flux<EarningSchedule> findByPeriodEndBeforeAndEarnedAtPeriodEndIsNull(LocalDate asOf);

    /**
     * Endorsement retro-recompute scan (Phase 9): every row for a policy from
     * {@code effectiveFrom} onward.
     */
    Flux<EarningSchedule> findByPolicyIdAndPolicySourceAndPeriodStartGreaterThanEqual(
            UUID policyId, String policySource, LocalDate effectiveFrom);

    /** Idempotent replay guard for endorsement rewrites (Phase 9). */
    Mono<Void> deleteByEndorsementId(UUID endorsementId);
}
