package com.medfund.claims.pmb;

import com.medfund.claims.entity.Claim;
import reactor.core.publisher.Mono;

/**
 * Default {@link PmbClassifier} — returns {@link PmbClassification#NOT_PMB} for
 * every claim. Phase 17 replaces this with a rules-engine-backed classifier
 * ({@code RuleCategory.PMB_CLASSIFICATION}) that consults the tenant's seeded
 * PMB rules; until then the backfill job can still run end-to-end without
 * throwing, and non-classifying tenants keep the DB-default {@code is_pmb=false}.
 *
 * <p>Wired as a fallback bean via {@link PmbConfig} — Phase 17 lands its own
 * bean with the same interface and this one drops out automatically.
 */
public class NoOpPmbClassifier implements PmbClassifier {

    @Override
    public Mono<PmbClassification> classify(Claim claim) {
        return Mono.just(PmbClassification.NOT_PMB);
    }
}
