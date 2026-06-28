package com.medfund.contributions.service;

import com.medfund.contributions.entity.Contribution;
import com.medfund.contributions.service.factenricher.LineFactEnricher;
import com.medfund.rules.fact.ContributionFact;
import com.medfund.rules.fact.TimeFact;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Translates a {@link Contribution} entity into a {@link ContributionFact}
 * + {@link TimeFact} pair for rules-engine evaluation.
 *
 * <p>Dispatches to the line-specific {@link LineFactEnricher} (HEALTH,
 * MOTOR, PROPERTY, …) by looking up the scheme's {@code insurance_line}.
 * The enricher reads its own per-line risk signals and populates both
 * the legacy typed fields on the fact AND the line-agnostic
 * {@code attributes} map.
 *
 * <p>Adding a new line is "drop one {@code @Component} that implements
 * {@link LineFactEnricher}" — no edit here. Lines with no registered
 * enricher fall through to the base fact (no enrichment), keeping
 * pricing deterministic.
 */
@Slf4j
@Component
public class ContributionFactBuilder {

    private final DatabaseClient db;
    private final Map<String, LineFactEnricher> enrichers;

    public ContributionFactBuilder(DatabaseClient db, List<LineFactEnricher> enricherList) {
        this.db = db;
        this.enrichers = (enricherList != null ? enricherList : List.<LineFactEnricher>of())
                .stream()
                .collect(Collectors.toMap(LineFactEnricher::supportedLine, e -> e));
    }

    public Mono<Facts> build(Contribution contribution) {
        ContributionFact base = toFact(contribution);
        TimeFact time = TimeFact.of(LocalDate.now());

        return resolveInsuranceLine(contribution.getSchemeId())
                .flatMap(line -> {
                    LineFactEnricher enricher = enrichers.get(line);
                    if (enricher == null) {
                        log.debug("[contribution-fact] no enricher for line {} — using base fact", line);
                        return Mono.just(base);
                    }
                    return enricher.enrich(base, contribution);
                })
                .map(fact -> new Facts(fact, time));
    }

    /**
     * Look up the scheme's insurance_line so the builder can pick the
     * right enricher. Defaults to HEALTH on missing scheme / null
     * column / lookup error — the only line shipped today, and the
     * safest fallback if a tenant's scheme is missing the column.
     */
    private Mono<String> resolveInsuranceLine(UUID schemeId) {
        if (schemeId == null) return Mono.just("HEALTH");
        return db.sql("SELECT insurance_line FROM schemes WHERE id = :id")
                .bind("id", schemeId)
                .map(row -> {
                    Object v = row.get("insurance_line");
                    return v != null ? v.toString() : "HEALTH";
                })
                .one()
                .defaultIfEmpty("HEALTH")
                .onErrorReturn("HEALTH");
    }

    private ContributionFact toFact(Contribution c) {
        ContributionFact f = new ContributionFact();
        f.setContributionId(c.getId() != null ? c.getId().toString() : null);
        f.setMemberId(c.getMemberId() != null ? c.getMemberId().toString() : null);
        f.setSchemeId(c.getSchemeId() != null ? c.getSchemeId().toString() : null);
        f.setGroupId(c.getGroupId() != null ? c.getGroupId().toString() : null);
        f.setCurrencyCode(c.getCurrencyCode());
        f.setPeriodStart(c.getPeriodStart());
        f.setPeriodEnd(c.getPeriodEnd());
        f.setBaseAmount(c.getAmount());
        f.setPremiumAmount(c.getAmount());
        f.setPaid("paid".equalsIgnoreCase(c.getStatus()));
        if (c.getPeriodEnd() != null) {
            LocalDate today = LocalDate.now();
            if (!"paid".equalsIgnoreCase(c.getStatus()) && today.isAfter(c.getPeriodEnd())) {
                f.setDaysOverdue((int) ChronoUnit.DAYS.between(c.getPeriodEnd(), today));
            }
        }
        return f;
    }

    /** Translates a fact (after rules have run) back into the contribution row. */
    public void applyOutcomes(Contribution contribution, ContributionFact fact) {
        if (fact.getPremiumAmount() != null) {
            contribution.setAmount(fact.getPremiumAmount());
        }
        // Late fees on first cut just go to the audit trail — wiring them into
        // a separate billable-row will land in a follow-up. Pricing-only flow
        // for now.
    }

    /** Bundle of the per-contribution + time facts handed to the rules engine. */
    public record Facts(ContributionFact contribution, TimeFact time) {}
}
