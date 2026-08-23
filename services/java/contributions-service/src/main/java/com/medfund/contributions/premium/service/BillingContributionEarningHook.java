package com.medfund.contributions.premium.service;

import com.medfund.contributions.entity.Contribution;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Called from {@code BillingService} once a HEALTH {@link Contribution}
 * row is persisted, inside the same reactive transaction. Writes one
 * {@code earning_schedule} row per contribution — HEALTH earns entirely
 * within its billing period per Phase 12 §A U2, so {@code earned_at_period_end}
 * is set to the row's amount at creation.
 *
 * <p>Failures inside the earning-schedule write are logged with the full
 * cause chain but do not fail the billing operation: earning-schedule is
 * a downstream analytics artefact, and a broken write can be caught up by
 * the {@code PremiumEarningExecutor} backfill path (Phase 5 §6). The
 * contribution itself is the source of truth for reconciliation.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BillingContributionEarningHook {

    private final EarningScheduleService earningScheduleService;

    public Mono<Contribution> onContributionCreated(Contribution contribution) {
        return earningScheduleService.writeContributionSchedule(contribution)
                .onErrorResume(err -> {
                    log.error("earning_schedule write failed for contribution={} — {} : {}. "
                                    + "Contribution persisted; backfill via PremiumEarningExecutor.",
                            contribution.getId(), err.getClass().getSimpleName(), err.getMessage(), err);
                    return Mono.empty();
                })
                .thenReturn(contribution);
    }
}
