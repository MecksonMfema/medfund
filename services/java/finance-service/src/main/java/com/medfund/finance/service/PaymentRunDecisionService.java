package com.medfund.finance.service;

import com.medfund.finance.entity.PaymentRunItem;
import com.medfund.rules.fact.PaymentRunFact;
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
 * Runs PROVIDER_PAYMENT + RECONCILIATION rules for a candidate
 * {@link PaymentRunItem} and returns the resulting fact (which the caller
 * can read for {@code scheduled}, {@code scheduledDate},
 * {@code withholdAmount}, {@code reconciled}).
 *
 * <p>Tenants without finance rules see the fact passed straight through —
 * caller falls back to whatever default scheduling/withholding behaviour
 * already exists in the workflow.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentRunDecisionService {

    private final TenantRuleLoader      loader;
    private final RuleEvaluationService evaluator;
    private final PaymentRunFactBuilder factBuilder;

    /**
     * Evaluate the run item and return the populated fact. The fact carries
     * every action outcome the rules emitted (scheduling decision, withhold
     * amount, reconciliation flag, plus a trace in {@code results}).
     */
    public Mono<PaymentRunFact> decide(PaymentRunItem item, BigDecimal advancePaid) {
        return Mono.deferContextual(ctx -> {
            UUID tenantId = parseTenant(TenantContext.get(ctx));
            if (tenantId == null) {
                log.debug("[paymentrun] no tenant context — skipping rules for run-item {}", item.getId());
                return factBuilder.build(item, advancePaid)
                        .map(PaymentRunFactBuilder.Facts::paymentRun);
            }
            return loader.ensureLoaded(tenantId)
                .then(factBuilder.build(item, advancePaid))
                .flatMap(facts -> evaluator
                        .evaluate(tenantId.toString(), facts.paymentRun(), facts.time())
                        .thenReturn(facts.paymentRun()))
                .doOnNext(f -> {
                    factBuilder.applyOutcomes(item, f);
                    if (f.getResults() != null && !f.getResults().isEmpty()) {
                        log.debug("[paymentrun] tenant {} provider {}: {} outcomes",
                                tenantId, item.getProviderId(), f.getResults().size());
                    }
                });
        });
    }

    /** Convenience overload — caller doesn't track advance payments yet. */
    public Mono<PaymentRunFact> decide(PaymentRunItem item) {
        return decide(item, BigDecimal.ZERO);
    }

    private UUID parseTenant(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return UUID.fromString(raw); } catch (IllegalArgumentException e) { return null; }
    }
}
