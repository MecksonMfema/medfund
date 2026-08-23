package com.medfund.contributions.premium.scheduler;

import com.medfund.contributions.premium.service.EarningScheduleClosureService;
import com.medfund.shared.scheduler.JobType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PremiumEarningExecutorTest {

    @Mock EarningScheduleClosureService closureService;

    @InjectMocks PremiumEarningExecutor executor;

    @Test
    void getJobType_isPremiumEarning() {
        assertThat(executor.getJobType()).isEqualTo(JobType.PREMIUM_EARNING);
    }

    @Test
    void execute_delegatesToClosureService() {
        String tenantId = UUID.randomUUID().toString();
        when(closureService.closeExpiredPeriodsForTenant(tenantId)).thenReturn(Mono.empty());

        StepVerifier.create(executor.execute(tenantId, "{}")).verifyComplete();

        verify(closureService).closeExpiredPeriodsForTenant(tenantId);
    }
}
