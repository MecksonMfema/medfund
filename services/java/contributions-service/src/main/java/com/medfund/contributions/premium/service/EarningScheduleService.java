package com.medfund.contributions.premium.service;

import com.medfund.contributions.entity.Contribution;
import com.medfund.contributions.premium.consumer.PolicyIssuedPayload;
import com.medfund.contributions.premium.entity.EarningSchedule;
import com.medfund.contributions.premium.repository.EarningScheduleRepository;
import com.medfund.rules.fact.PremiumFact;
import com.medfund.rules.service.RuleEvaluationService;
import com.medfund.rules.service.TenantRuleLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Writes {@code earning_schedule} rows for policies (via
 * {@code PolicyIssuedConsumer}) and contributions (via
 * {@code BillingContributionEarningHook}).
 *
 * <p>Annual-bind lines run through the tenant's {@code PREMIUM_EARNING}
 * agenda group so a rule picks the earning method for the fact; the
 * {@link PremiumEarningStripCalculator} then splits the written premium
 * across the coverage window's calendar months.
 *
 * <p>HEALTH earns entirely inside its billing period (Phase 12 §A U2), so
 * the hook writes one row per {@code Contribution} with
 * {@code earned_at_period_end} pre-set to the row amount.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EarningScheduleService {

    private static final String AGENDA_GROUP = "PREMIUM_EARNING";

    private final EarningScheduleRepository earningScheduleRepository;
    private final PremiumEarningStripCalculator stripCalculator;
    private final RuleEvaluationService ruleEvaluationService;
    private final TenantRuleLoader tenantRuleLoader;

    /**
     * Called by {@code PolicyIssuedConsumer} for annual-bind lines. Runs the
     * tenant's {@code PREMIUM_EARNING} rules to pick an earning method, then
     * persists one strip row per calendar month across the coverage window.
     */
    public Mono<Void> writeSchedule(PolicyIssuedPayload payload) {
        if (payload.writtenPremium() == null || payload.coverageStart() == null
                || payload.coverageEnd() == null) {
            log.warn("policy-issued payload missing written/coverage — skipping for policy={}", payload.policyId());
            return Mono.empty();
        }
        PremiumFact fact = buildFact(payload);
        UUID tenantUuid = parseUuid(payload.tenantId());
        Mono<Void> ensure = tenantUuid == null ? Mono.empty() : tenantRuleLoader.ensureLoaded(tenantUuid);
        return ensure
                .then(Mono.defer(() -> ruleEvaluationService.evaluateInGroup(
                        payload.tenantId() == null ? "" : payload.tenantId(),
                        AGENDA_GROUP, fact)))
                .then(Mono.defer(() -> writeRowsForFact(fact)));
    }

    /**
     * Called by {@code BillingContributionEarningHook} for every persisted
     * HEALTH {@link Contribution}. One row, {@code earned = written}.
     */
    public Mono<Void> writeContributionSchedule(Contribution contribution) {
        if (contribution == null || contribution.getAmount() == null
                || contribution.getCurrencyCode() == null
                || contribution.getPeriodStart() == null || contribution.getPeriodEnd() == null) {
            return Mono.empty();
        }
        EarningSchedule row = new EarningSchedule();
        row.setPolicyId(contribution.getId());
        row.setPolicySource("CONTRIBUTION");
        row.setInsuranceLine("HEALTH");
        row.setPeriodStart(contribution.getPeriodStart());
        row.setPeriodEnd(contribution.getPeriodEnd());
        row.setWrittenAmount(contribution.getAmount());
        row.setEarnedAtPeriodEnd(contribution.getAmount());
        row.setCurrencyCode(contribution.getCurrencyCode());
        row.setEarningMethod("DAILY_LINEAR");
        row.setPortfolioId(contribution.getPortfolioId());
        row.setCohortId(contribution.getCohortId());
        return earningScheduleRepository.save(row).then();
    }

    private Mono<Void> writeRowsForFact(PremiumFact fact) {
        List<EarningSchedule> rows = stripCalculator.split(fact);
        if (rows.isEmpty()) return Mono.empty();
        return Flux.fromIterable(rows)
                .concatMap(earningScheduleRepository::save)
                .then();
    }

    private static PremiumFact buildFact(PolicyIssuedPayload payload) {
        PremiumFact fact = new PremiumFact();
        fact.setPolicyId(payload.policyId() == null ? null : payload.policyId().toString());
        fact.setPolicySource(payload.policySource());
        fact.setInsuranceLine(payload.insuranceLine());
        fact.setTenantId(payload.tenantId());
        fact.setWrittenPremium(payload.writtenPremium());
        fact.setCurrencyCode(payload.currencyCode());
        fact.setCoverageStart(payload.coverageStart());
        fact.setCoverageEnd(payload.coverageEnd());
        fact.setBoundAt(payload.boundAt() == null ? null : payload.boundAt().atOffset(ZoneOffset.UTC));
        fact.setPortfolioId(payload.portfolioId() == null ? null : payload.portfolioId().toString());
        fact.setCohortId(payload.cohortId() == null ? null : payload.cohortId().toString());
        return fact;
    }

    private static UUID parseUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
    }
}
