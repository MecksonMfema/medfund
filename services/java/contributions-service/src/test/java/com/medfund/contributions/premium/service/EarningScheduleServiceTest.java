package com.medfund.contributions.premium.service;

import com.medfund.contributions.entity.Contribution;
import com.medfund.contributions.premium.consumer.PolicyIssuedPayload;
import com.medfund.contributions.premium.entity.EarningSchedule;
import com.medfund.contributions.premium.repository.EarningScheduleRepository;
import com.medfund.rules.service.RuleEvaluationService;
import com.medfund.rules.service.TenantRuleLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Guards the two Phase 12 §A entry points:
 * <ul>
 *   <li>{@link EarningScheduleService#writeSchedule(PolicyIssuedPayload)} for
 *       annual-bind lines — must ensure tenant rules are loaded, evaluate the
 *       {@code PREMIUM_EARNING} agenda, then persist one strip row per period.</li>
 *   <li>{@link EarningScheduleService#writeContributionSchedule(Contribution)}
 *       for HEALTH — must persist a single row with earned = amount and skip
 *       when key fields are absent.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class EarningScheduleServiceTest {

    @Mock EarningScheduleRepository earningScheduleRepository;
    @Mock RuleEvaluationService ruleEvaluationService;
    @Mock TenantRuleLoader tenantRuleLoader;

    private EarningScheduleService service;
    private final PremiumEarningStripCalculator calc = new PremiumEarningStripCalculator();

    @BeforeEach
    void setUp() {
        service = new EarningScheduleService(earningScheduleRepository, calc,
                ruleEvaluationService, tenantRuleLoader);
    }

    @Test
    void writeSchedule_annualPolicy_evaluatesRulesThenPersistsStrip() {
        UUID policyId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        when(tenantRuleLoader.ensureLoaded(any(UUID.class))).thenReturn(Mono.empty());
        when(ruleEvaluationService.evaluateInGroup(anyString(), anyString(), any()))
                .thenReturn(Mono.just(Collections.emptyList()));
        when(earningScheduleRepository.save(any(EarningSchedule.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(service.writeSchedule(payload(tenantId, policyId)))
                .verifyComplete();

        verify(tenantRuleLoader).ensureLoaded(tenantId);
        verify(ruleEvaluationService).evaluateInGroup(eq(tenantId.toString()), eq("PREMIUM_EARNING"), any());
        // 12-month coverage → 12 strip rows persisted.
        verify(earningScheduleRepository, times(12)).save(any(EarningSchedule.class));
    }

    @Test
    void writeSchedule_missingWrittenPremium_skipsWithoutEvaluatingRules() {
        UUID policyId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        PolicyIssuedPayload payload = new PolicyIssuedPayload(
                tenantId.toString(), policyId, "POL-123", "LIFE_POLICY", "LIFE",
                null, "USD",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                Instant.parse("2026-01-01T00:00:00Z"),
                UUID.randomUUID(), null, null, null);

        StepVerifier.create(service.writeSchedule(payload)).verifyComplete();

        verify(tenantRuleLoader, never()).ensureLoaded(any());
        verify(ruleEvaluationService, never()).evaluateInGroup(anyString(), anyString(), any());
        verify(earningScheduleRepository, never()).save(any(EarningSchedule.class));
    }

    @Test
    void writeContributionSchedule_health_writesSingleRowEarnedEqualsAmount() {
        Contribution c = healthContribution();
        UUID portfolioId = UUID.randomUUID();
        c.setPortfolioId(portfolioId);
        when(earningScheduleRepository.save(any(EarningSchedule.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(service.writeContributionSchedule(c)).verifyComplete();

        ArgumentCaptor<EarningSchedule> captor = ArgumentCaptor.forClass(EarningSchedule.class);
        verify(earningScheduleRepository).save(captor.capture());
        EarningSchedule row = captor.getValue();
        assertThat(row.getPolicySource()).isEqualTo("CONTRIBUTION");
        assertThat(row.getInsuranceLine()).isEqualTo("HEALTH");
        assertThat(row.getWrittenAmount()).isEqualByComparingTo("50.00");
        assertThat(row.getEarnedAtPeriodEnd()).isEqualByComparingTo("50.00");
        assertThat(row.getPortfolioId()).isEqualTo(portfolioId);
        assertThat(row.getEarningMethod()).isEqualTo("DAILY_LINEAR");
    }

    @Test
    void writeContributionSchedule_missingFields_skipsWithoutWriting() {
        Contribution c = new Contribution();
        c.setId(UUID.randomUUID());
        c.setAmount(new BigDecimal("50"));
        // Missing currency + periods on purpose.

        StepVerifier.create(service.writeContributionSchedule(c)).verifyComplete();

        verify(earningScheduleRepository, never()).save(any(EarningSchedule.class));
    }

    private static PolicyIssuedPayload payload(UUID tenantId, UUID policyId) {
        return new PolicyIssuedPayload(
                tenantId.toString(), policyId, "POL-42", "LIFE_POLICY", "LIFE",
                new BigDecimal("1200"), "USD",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                Instant.parse("2026-01-01T00:00:00Z"),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null);
    }

    private static Contribution healthContribution() {
        Contribution c = new Contribution();
        c.setId(UUID.randomUUID());
        c.setMemberId(UUID.randomUUID());
        c.setAmount(new BigDecimal("50.00"));
        c.setCurrencyCode("USD");
        c.setPeriodStart(LocalDate.of(2026, 3, 1));
        c.setPeriodEnd(LocalDate.of(2026, 3, 31));
        return c;
    }
}
