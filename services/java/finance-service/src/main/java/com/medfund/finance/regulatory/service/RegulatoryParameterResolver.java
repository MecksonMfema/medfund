package com.medfund.finance.regulatory.service;

import com.medfund.rules.fact.RegulatoryParameterFact;
import com.medfund.rules.service.RuleEvaluationService;
import com.medfund.rules.service.TenantRuleLoader;
import com.medfund.shared.report.regulatory.RegulatoryDefaultsLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves a single regulator parameter value for the given
 * (tenant, jurisdiction, parameterKey, effectiveDate). Phase 16 §Phase-15
 * REG15 — the rules-engine escape hatch that fronts every
 * {@code regulatory-defaults/{jurisdiction}/*.yaml} lookup.
 *
 * <p>Resolution order:
 * <ol>
 *   <li>Fire the tenant's {@code REGULATORY_PARAMETER} rules against a
 *       {@link RegulatoryParameterFact} carrying the lookup key +
 *       jurisdiction + effective-date. If any rule sets
 *       {@code parameterValue} to a non-null, return that.</li>
 *   <li>Fall back to {@link RegulatoryDefaultsLoader#lookup} for the
 *       bundled YAML default.</li>
 *   <li>Throw {@link RegulatoryParameterMissingException} when both are
 *       empty — a template-authoring gap that must not silently zero a
 *       report cell on a live regulator submission.</li>
 * </ol>
 *
 * <p>Callers are the per-regulator calculators (IPEC, CMS, NAIC Schedule P,
 * NAIC Schedule F, and every future regulator) — see the Phase 15.4 retrofit.
 * The rules-engine per-tenant KieContainer isolation invariant from
 * {@code bug_rules_engine_tenant_isolation} continues to apply; rule
 * evaluation errors resolve to the YAML fallback path with a WARN log so a
 * rules-engine blip fails-over to the bundled default rather than blocking
 * the report entirely.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RegulatoryParameterResolver {

    static final String AGENDA_GROUP = "REGULATORY_PARAMETER";

    private final TenantRuleLoader tenantRuleLoader;
    private final RuleEvaluationService ruleEvaluationService;
    private final RegulatoryDefaultsLoader defaultsLoader;

    /**
     * Resolve the parameter — rules-engine → bundled YAML → fail-loud.
     * Never returns null; the resolved value is always non-null.
     *
     * @param tenantId       tenant whose rules-engine to consult; must be non-null
     * @param jurisdiction   {@code TenantJurisdiction} name (e.g. {@code ZW_IPEC_SHORT_TERM})
     * @param parameterKey   parameter identifier as it appears in the YAML {@code parameters:} block
     * @param effectiveDate  report period-end for effective-dating rules and YAML picks
     */
    public Mono<BigDecimal> resolve(UUID tenantId, String jurisdiction, String parameterKey,
                                    LocalDate effectiveDate) {
        if (tenantId == null) {
            return Mono.error(new IllegalArgumentException("tenantId required"));
        }
        if (jurisdiction == null || jurisdiction.isBlank()) {
            return Mono.error(new IllegalArgumentException("jurisdiction required"));
        }
        if (parameterKey == null || parameterKey.isBlank()) {
            return Mono.error(new IllegalArgumentException("parameterKey required"));
        }

        RegulatoryParameterFact fact = new RegulatoryParameterFact();
        fact.setParameterKey(parameterKey);
        fact.setJurisdiction(jurisdiction);
        fact.setEffectiveFrom(effectiveDate);

        return tenantRuleLoader.ensureLoaded(tenantId)
                .then(ruleEvaluationService.evaluateInGroup(
                        tenantId.toString(), AGENDA_GROUP, fact))
                .map(ignored -> Optional.ofNullable(fact.getParameterValue()))
                .onErrorResume(err -> {
                    log.warn("[regulatory-parameter] tenant {} rule eval failed for {}/{} — falling back to YAML ({})",
                            tenantId, jurisdiction, parameterKey, err.getMessage());
                    return Mono.just(Optional.empty());
                })
                .flatMap(rulesValue -> {
                    if (rulesValue.isPresent()) {
                        log.debug("[regulatory-parameter] tenant {} rule override {}/{} = {} (rule: {})",
                                tenantId, jurisdiction, parameterKey,
                                rulesValue.get().toPlainString(), fact.getAppliedRuleName());
                        return Mono.just(rulesValue.get());
                    }
                    Optional<BigDecimal> yamlValue = defaultsLoader.lookup(
                            jurisdiction, parameterKey, effectiveDate);
                    if (yamlValue.isPresent()) {
                        return Mono.just(yamlValue.get());
                    }
                    return Mono.error(new RegulatoryParameterMissingException(
                            "No value for regulatory parameter '" + parameterKey
                                    + "' - jurisdiction " + jurisdiction
                                    + ", effective-date " + effectiveDate
                                    + ". Neither a tenant REGULATORY_PARAMETER rule fired nor a "
                                    + "bundled regulatory-defaults/" + jurisdiction + "/*.yaml key exists."));
                });
    }
}
