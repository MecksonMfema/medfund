package com.medfund.contributions.premium.service;

import com.medfund.contributions.entity.Contribution;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The hook is a thin façade so its main job is preserving the payload's
 * ordering (write schedule, then propagate the contribution) and swallowing
 * downstream failures so a broken earning-schedule write doesn't fail
 * billing.
 */
@ExtendWith(MockitoExtension.class)
class BillingContributionEarningHookTest {

    @Mock EarningScheduleService earningScheduleService;

    @InjectMocks BillingContributionEarningHook hook;

    @Test
    void onContributionCreated_delegatesAndReturnsContribution() {
        Contribution c = contribution();
        when(earningScheduleService.writeContributionSchedule(any())).thenReturn(Mono.empty());

        StepVerifier.create(hook.onContributionCreated(c))
                .expectNext(c)
                .verifyComplete();

        verify(earningScheduleService).writeContributionSchedule(c);
    }

    @Test
    void onContributionCreated_swallowsDownstreamFailure() {
        Contribution c = contribution();
        when(earningScheduleService.writeContributionSchedule(any()))
                .thenReturn(Mono.error(new IllegalStateException("db down")));

        // Billing must not fail if the earning-schedule projection breaks —
        // executor backfill will catch up.
        StepVerifier.create(hook.onContributionCreated(c))
                .expectNext(c)
                .verifyComplete();
    }

    @Test
    void onContributionCreated_completesEvenWithZeroSchedule() {
        Contribution c = contribution();
        when(earningScheduleService.writeContributionSchedule(any())).thenReturn(Mono.empty());

        StepVerifier.create(hook.onContributionCreated(c))
                .expectNext(c)
                .verifyComplete();
    }

    private static Contribution contribution() {
        Contribution c = new Contribution();
        c.setId(UUID.randomUUID());
        c.setAmount(new BigDecimal("50"));
        c.setCurrencyCode("USD");
        c.setPeriodStart(LocalDate.of(2026, 3, 1));
        c.setPeriodEnd(LocalDate.of(2026, 3, 31));
        return c;
    }
}
