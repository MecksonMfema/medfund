package com.medfund.contributions.service;

import com.medfund.contributions.entity.Contribution;
import com.medfund.rules.fact.ContributionFact;
import com.medfund.rules.fact.TimeFact;
import com.medfund.rules.service.RuleEvaluationService;
import com.medfund.rules.service.TenantRuleLoader;
import com.medfund.shared.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Runs the tenant's CONTRIBUTION_PRICING + CONTRIBUTION_BILLING rules over a
 * draft {@link Contribution} and writes the resulting premium / late-fee
 * back onto the row.
 *
 * <p>Rules read from {@link ContributionFact} (member age / region /
 * dependant count / scheme id / period dates) and emit by mutating it
 * (e.g. {@code SET_PREMIUM} writes {@code premiumAmount},
 * {@code APPLY_LOADED_PREMIUM} multiplies the loading factor,
 * {@code APPLY_LATE_FEE} accumulates {@code totalLateFees}). After the
 * engine returns, the fact carries the final state.
 *
 * <p>If no rules are configured, the contribution's existing amount is
 * preserved untouched — tenants that haven't authored pricing rules still
 * get the legacy behaviour (caller-supplied amount).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContributionPricingService {

    private final TenantRuleLoader        loader;
    private final RuleEvaluationService   evaluator;
    private final ContributionFactBuilder factBuilder;

    /**
     * Price the contribution. Updates {@code contribution.amount} in place
     * if any pricing rule fired, and returns the populated fact for
     * downstream callers that want to surface late fees, loading factors,
     * etc. into the audit trail.
     */
    public Mono<ContributionFact> price(Contribution contribution) {
        return Mono.deferContextual(ctx -> {
            String raw = TenantContext.get(ctx);
            UUID tenantId = parseTenant(raw);
            if (tenantId == null) {
                log.debug("[pricing] no tenant context — skipping rules");
                return factBuilder.build(contribution).map(ContributionFactBuilder.Facts::contribution);
            }

            return loader.ensureLoaded(tenantId)
                .then(factBuilder.build(contribution))
                .flatMap(facts -> evaluator
                        .evaluate(tenantId.toString(), facts.contribution(), facts.time())
                        .thenReturn(facts.contribution()))
                .doOnNext(c -> {
                    factBuilder.applyOutcomes(contribution, c);
                    if (c.getResults() != null && !c.getResults().isEmpty()) {
                        log.debug("[pricing] tenant {} contribution {} — {} rule outcomes",
                                tenantId, contribution.getId(), c.getResults().size());
                    }
                });
        });
    }

    /**
     * Convenience overload — returns the priced amount only. Useful for
     * call sites that want a one-line "apply pricing then continue" flow.
     */
    public Mono<BigDecimal> priceAmount(Contribution contribution) {
        return price(contribution).map(ContributionFact::getPremiumAmount);
    }

    private UUID parseTenant(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return UUID.fromString(raw); } catch (IllegalArgumentException e) { return null; }
    }
}
