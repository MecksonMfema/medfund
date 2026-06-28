package com.medfund.contributions.service.factenricher;

import com.medfund.contributions.entity.Contribution;
import com.medfund.rules.fact.ContributionFact;
import reactor.core.publisher.Mono;

/**
 * Per-insurance-line enrichment of {@link ContributionFact} with
 * line-specific risk signals. HEALTH reads from {@code members
 * .medical_history}; MOTOR reads vehicle attributes; PROPERTY reads
 * the property's construction / location / security signals; etc.
 *
 * <p>Enrichers populate both the relevant typed fields on the fact
 * (for legacy rules that reference them by name) AND the line-agnostic
 * {@code attributes} map (the contract for new rules + the AI scorer).
 *
 * <p>{@link com.medfund.contributions.service.ContributionFactBuilder}
 * collects every {@code @Component} that implements this interface
 * and routes by {@link #supportedLine()} matched against the
 * contribution's scheme {@code insurance_line}.
 */
public interface LineFactEnricher {

    /** Insurance-line code this enricher handles (HEALTH, VEHICLE, …). */
    String supportedLine();

    /**
     * Read line-specific signals from the database and write them onto
     * the fact. Should be defensive: missing data yields the base fact
     * unchanged rather than erroring out — pricing rules should guard
     * on positive matches.
     */
    Mono<ContributionFact> enrich(ContributionFact base, Contribution contribution);
}
