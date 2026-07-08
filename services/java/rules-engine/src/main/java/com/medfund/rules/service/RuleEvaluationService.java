package com.medfund.rules.service;

import com.medfund.rules.engine.TenantRuleEngine;
import com.medfund.rules.fact.ClaimFact;
import com.medfund.rules.fact.DependantFact;
import com.medfund.rules.fact.FamilyFact;
import com.medfund.rules.fact.MemberFact;
import com.medfund.rules.fact.ProviderFact;
import com.medfund.rules.fact.RuleResult;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

/**
 * Higher-level service that wraps {@link TenantRuleEngine} with reactive {@link Mono}
 * support and provides convenience methods for claims adjudication.
 * <p>
 * All evaluation methods schedule the blocking Drools execution on
 * {@link Schedulers#boundedElastic()} to avoid blocking the event loop.
 */
@Service
public class RuleEvaluationService {

    private final TenantRuleEngine tenantRuleEngine;

    public RuleEvaluationService(TenantRuleEngine tenantRuleEngine) {
        this.tenantRuleEngine = tenantRuleEngine;
    }

    /**
     * Evaluate a claim against the tenant's rules.
     *
     * @param tenantId the tenant identifier
     * @param claim    the claim fact
     * @param member   the member fact
     * @param provider the provider fact
     * @return a Mono emitting the list of rule results
     */
    public Mono<List<RuleResult>> evaluateClaim(String tenantId, ClaimFact claim,
                                                 MemberFact member, ProviderFact provider) {
        return Mono.fromCallable(() -> tenantRuleEngine.evaluate(tenantId, claim, member, provider))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Evaluate a claim that includes a dependant against the tenant's rules.
     *
     * @param tenantId  the tenant identifier
     * @param claim     the claim fact
     * @param member    the member fact
     * @param dependant the dependant fact
     * @param provider  the provider fact
     * @return a Mono emitting the list of rule results
     */
    public Mono<List<RuleResult>> evaluateClaimWithDependant(String tenantId, ClaimFact claim,
                                                              MemberFact member,
                                                              DependantFact dependant,
                                                              ProviderFact provider) {
        return Mono.fromCallable(() ->
                        tenantRuleEngine.evaluate(tenantId, claim, member, dependant, provider))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Evaluate a claim that includes family (pooled benefits) against the tenant's rules.
     *
     * @param tenantId the tenant identifier
     * @param claim    the claim fact
     * @param member   the member fact
     * @param family   the family fact for pooled benefit checks
     * @param provider the provider fact
     * @return a Mono emitting the list of rule results
     */
    public Mono<List<RuleResult>> evaluateClaimWithFamily(String tenantId, ClaimFact claim,
                                                           MemberFact member, FamilyFact family,
                                                           ProviderFact provider) {
        return Mono.fromCallable(() ->
                        tenantRuleEngine.evaluate(tenantId, claim, member, family, provider))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Generic evaluation entry-point for non-claims domains (contributions,
     * member-lifecycle, finance, …). The caller passes whatever fact mix
     * the domain rules expect; the engine inserts each non-null fact into
     * the {@code KieSession} and fires all rules.
     *
     * <p>Most domains read their action outcomes off their own fact instance
     * after the call returns (e.g. {@code contributionFact.getPremiumAmount()})
     * — the returned {@code RuleResult} list is sourced from
     * {@link ClaimFact#getResults()} only, so it'll be empty when no
     * {@code ClaimFact} is in the inserted set.
     */
    public Mono<List<RuleResult>> evaluate(String tenantId, Object... facts) {
        return Mono.fromCallable(() -> tenantRuleEngine.evaluate(tenantId, facts))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Category-scoped evaluation. Only rules tagged with the given agenda-group
     * fire — everything else stays dormant. Used by claims-service
     * {@code ProrationService} to run BENEFIT_PRORATION rules in adjudication
     * stage 3 without triggering the stage-7 tenant-rules sweep.
     */
    public Mono<List<RuleResult>> evaluateInGroup(String tenantId, String agendaGroup,
                                                  Object... facts) {
        return Mono.fromCallable(() -> tenantRuleEngine.evaluateInGroup(tenantId, agendaGroup, facts))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
