package com.medfund.claims.pmb;

import com.medfund.claims.entity.Claim;
import reactor.core.publisher.Mono;

/**
 * Classifies a single claim as PMB / non-PMB per the tenant's currently-active
 * {@code RuleCategory.PMB_CLASSIFICATION} rule set (Phase 17). Phase 16 ships
 * only the SPI + a {@link NoOpPmbClassifier} default so the backfill batch job
 * compiles and can be exercised in tests; Phase 17 replaces the default with
 * the real rules-engine-backed classifier.
 *
 * <p>Implementations must be tenant-scoped — the caller is expected to have
 * pre-populated {@link com.medfund.shared.tenant.TenantContext} on the reactor
 * chain so a per-tenant rule sweep hits the right rule set.
 */
public interface PmbClassifier {

    /**
     * Classify a single claim.
     *
     * @return {@link PmbClassification#NOT_PMB} when no rule matched, otherwise
     *   the matched {@link PmbClassification#pmb(String) pmb(code)}. Never {@code null}.
     */
    Mono<PmbClassification> classify(Claim claim);
}
