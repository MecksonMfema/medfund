package com.medfund.contributions.premium.scheduler;

import com.medfund.contributions.premium.service.EarningScheduleClosureService;
import com.medfund.shared.scheduler.JobExecutor;
import com.medfund.shared.scheduler.JobType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Nightly job: close every {@code earning_schedule} row whose period has
 * expired, then refresh the {@code member_first_contribution} materialised
 * view (Phase 12 §A U10, grill note 7). Chunk size and progress tracking
 * live in {@link EarningScheduleClosureService}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PremiumEarningExecutor implements JobExecutor {

    private final EarningScheduleClosureService closureService;

    @Override
    public JobType getJobType() { return JobType.PREMIUM_EARNING; }

    @Override
    public Mono<Void> execute(String tenantId, String settings) {
        log.info("PremiumEarningExecutor tenant={} settings={}", tenantId, settings);
        return closureService.closeExpiredPeriodsForTenant(tenantId);
    }
}
