package com.medfund.claims.service;

import com.medfund.claims.entity.Claim;
import com.medfund.rules.fact.ClaimFact;
import com.medfund.rules.fact.RuleResult;
import com.medfund.rules.fact.SchemeChangeContext;
import com.medfund.rules.model.ProrationStrategy;
import com.medfund.rules.service.RuleEvaluationService;
import com.medfund.rules.service.TenantRuleLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Resolves the effective annual limit for a claim's benefit when the member
 * has changed scheme since the start of the current calendar year.
 *
 * <p>Called from {@link AdjudicationPipeline#benefitLimitCheck} in stage 3.
 * Members with no {@code scheme_changes} row on or before {@code service_date}
 * short-circuit to {@code ProrationStrategy.NONE} — the fresh claim uses the
 * benefit's raw {@code annual_limit} exactly as before this feature landed.
 *
 * <p>Resolution order:
 * <ol>
 *   <li>Fire {@code BENEFIT_PRORATION} rules (agenda-gated) — a matching rule
 *       may call {@code applyProrationStrategy(NAME)} which sets
 *       {@link ClaimFact#getProrationStrategy()}.</li>
 *   <li>Fall through to {@code tenant_proration_config} — the direction-aware
 *       {@code upgrade_strategy / downgrade_strategy / currency_strategy}
 *       column when the row's {@code default_strategy} is
 *       {@code HYBRID_BY_DIRECTION}, else the flat {@code default_strategy}.</li>
 *   <li>Fall through to {@link ProrationStrategy#NONE}.</li>
 * </ol>
 *
 * <p>Cross-currency scheme changes (previous benefit and new benefit in
 * different currencies) short-circuit to {@code NONE} with a warning — real
 * cross-currency proration needs the exchange service pre-injected into the
 * SchemeChangeContext and is deferred.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProrationService {

    private static final String AGENDA_GROUP = "BENEFIT_PRORATION";

    private final DatabaseClient databaseClient;
    private final ClaimFactBuilder factBuilder;
    private final RuleEvaluationService ruleEvaluationService;
    private final TenantRuleLoader tenantRuleLoader;

    /**
     * @param tenantId       for tenant-rule loading + config lookup
     * @param claim          the claim under adjudication (used for memberId, benefitId, serviceDate)
     * @param newAnnualLimit the raw annual_limit on {@code scheme_benefits} for the claim's benefit
     * @param benefitName    the friendly name of the current benefit; used to match the same-type
     *                       benefit under the previous scheme (falls back to NONE if not found)
     * @param benefitCurrency the currency of the current benefit — cross-currency triggers fallback
     */
    public Mono<ProrationDecision> resolveEffectiveLimit(UUID tenantId,
                                                         Claim claim,
                                                         BigDecimal newAnnualLimit,
                                                         String benefitName,
                                                         String benefitCurrency) {
        LocalDate serviceDate = claim.getServiceDate() != null ? claim.getServiceDate() : LocalDate.now();

        return loadLatestSchemeChange(claim.getMemberId(), serviceDate)
                .flatMap(change ->
                        buildContext(change, claim.getMemberId(), claim.getBenefitId(),
                                     benefitName, newAnnualLimit, benefitCurrency, serviceDate)
                        .flatMap(ctx -> decide(tenantId, claim, ctx, newAnnualLimit)))
                .switchIfEmpty(Mono.defer(() -> sumConsumed(claim.getMemberId(), claim.getBenefitId(), serviceDate)
                        .map(total -> new ProrationDecision(
                                ProrationStrategy.NONE.name(),
                                newAnnualLimit,
                                total,
                                "No scheme change on or before service date — using raw annual limit"))));
    }

    // ── Data loads ────────────────────────────────────────────────────────────

    private Mono<SchemeChangeRow> loadLatestSchemeChange(UUID memberId, LocalDate serviceDate) {
        if (memberId == null) return Mono.empty();
        return databaseClient.sql("""
                SELECT id, from_scheme_id, to_scheme_id, effective_date, change_kind
                  FROM scheme_changes
                 WHERE member_id = :memberId
                   AND status = 'EFFECTIVE'
                   AND effective_date <= :serviceDate
                 ORDER BY effective_date DESC
                 LIMIT 1
                """)
                .bind("memberId", memberId)
                .bind("serviceDate", serviceDate)
                .map(row -> new SchemeChangeRow(
                        row.get("from_scheme_id", UUID.class),
                        row.get("to_scheme_id", UUID.class),
                        row.get("effective_date", LocalDate.class),
                        asString(row.get("change_kind"))))
                .one();
    }

    private Mono<SchemeChangeContext> buildContext(SchemeChangeRow change,
                                                   UUID memberId,
                                                   UUID currentBenefitId,
                                                   String benefitName,
                                                   BigDecimal newAnnualLimit,
                                                   String newCurrency,
                                                   LocalDate serviceDate) {
        // Find the previous scheme's benefit with the same name — that's the counterpart
        // whose consumption we prorate from. If it doesn't exist (new benefit added on
        // the new scheme, or renamed), the OLD-side fields stay null and strategies
        // that require them collapse to NONE via null-guards.
        Mono<PrevBenefit> prevBenefitMono = benefitName == null
                ? Mono.empty()
                : databaseClient.sql("""
                        SELECT id, annual_limit, currency_code
                          FROM scheme_benefits
                         WHERE scheme_id = :schemeId
                           AND name      = :name
                         LIMIT 1
                        """)
                        .bind("schemeId", change.fromSchemeId())
                        .bind("name",      benefitName)
                        .map(row -> new PrevBenefit(
                                row.get("id", UUID.class),
                                asDecimal(row.get("annual_limit")),
                                asString(row.get("currency_code"))))
                        .one();

        return prevBenefitMono
                .defaultIfEmpty(new PrevBenefit(null, null, newCurrency))
                .flatMap(prev -> {
                    Mono<BigDecimal> consumedPrev = prev.id() == null
                            ? Mono.just(BigDecimal.ZERO)
                            : sumConsumed(memberId, prev.id(), serviceDate);
                    Mono<BigDecimal> consumedNew = currentBenefitId == null
                            ? Mono.just(BigDecimal.ZERO)
                            : sumConsumed(memberId, currentBenefitId, serviceDate);
                    return Mono.zip(consumedPrev, consumedNew)
                            .map(t -> {
                                SchemeChangeContext ctx = new SchemeChangeContext();
                                ctx.setPrevSchemeId(asString(change.fromSchemeId()));
                                ctx.setNewSchemeId(asString(change.toSchemeId()));
                                ctx.setPrevBenefitId(asString(prev.id()));
                                ctx.setNewBenefitId(asString(currentBenefitId));
                                ctx.setPrevAnnualLimit(prev.annualLimit());
                                ctx.setNewAnnualLimit(newAnnualLimit);
                                ctx.setPrevCurrencyCode(prev.currencyCode());
                                ctx.setNewCurrencyCode(newCurrency);
                                ctx.setConsumedUnderPrevScheme(t.getT1());
                                ctx.setConsumedUnderNewScheme(t.getT2());
                                ctx.setEffectiveDate(change.effectiveDate());
                                ctx.setChangeKind(change.changeKind());
                                ctx.setDaysSinceChange((int) ChronoUnit.DAYS.between(
                                        change.effectiveDate(), serviceDate));
                                LocalDate yearEnd = LocalDate.of(serviceDate.getYear(), 12, 31);
                                ctx.setDaysRemainingInYear((int) ChronoUnit.DAYS.between(serviceDate, yearEnd) + 1);
                                ctx.setDaysInYear(serviceDate.lengthOfYear());
                                return ctx;
                            });
                });
    }

    private Mono<BigDecimal> sumConsumed(UUID memberId, UUID benefitId, LocalDate serviceDate) {
        if (memberId == null || benefitId == null) return Mono.just(BigDecimal.ZERO);
        // Restricted to the same calendar year as service_date — matches how
        // benefitLimitCheck already scopes its "used YTD" sum.
        return databaseClient.sql("""
                SELECT COALESCE(SUM(approved_amount), 0) AS used
                  FROM claims
                 WHERE member_id = :memberId
                   AND benefit_id = :benefitId
                   AND status IN ('ADJUDICATED', 'COMMITTED', 'PAID')
                   AND EXTRACT(YEAR FROM service_date) = EXTRACT(YEAR FROM :serviceDate::date)
                """)
                .bind("memberId", memberId)
                .bind("benefitId", benefitId)
                .bind("serviceDate", serviceDate)
                .map(row -> asDecimal(row.get("used")))
                .one()
                .defaultIfEmpty(BigDecimal.ZERO);
    }

    // ── Strategy resolution ───────────────────────────────────────────────────

    private Mono<ProrationDecision> decide(UUID tenantId, Claim claim,
                                           SchemeChangeContext ctx, BigDecimal newAnnualLimit) {
        if (ctx.isCrossCurrency()) {
            log.warn("Cross-currency scheme change for member {} (prev={} → new={}) — proration falls back to NONE",
                    claim.getMemberId(), ctx.getPrevCurrencyCode(), ctx.getNewCurrencyCode());
            return Mono.just(new ProrationDecision(
                    ProrationStrategy.NONE.name(),
                    newAnnualLimit,
                    ctx.totalConsumed(),
                    "Cross-currency scheme change — proration deferred, using raw new limit"));
        }

        return fireProrationRules(tenantId, claim, ctx)
                .map(strategyFromRule -> applyStrategy(strategyFromRule, ctx, newAnnualLimit,
                        "Rule-selected strategy " + strategyFromRule))
                .switchIfEmpty(loadTenantConfig(tenantId, ctx.getChangeKind())
                        .map(cfgStrategy -> applyStrategy(cfgStrategy, ctx, newAnnualLimit,
                                "Tenant-config strategy " + cfgStrategy))
                        .switchIfEmpty(Mono.just(applyStrategy(
                                ProrationStrategy.NONE, ctx, newAnnualLimit,
                                "No config or rule — platform default NONE"))));
    }

    /**
     * Fires BENEFIT_PRORATION rules. Emits the parsed strategy when a rule
     * called {@code applyProrationStrategy(NAME)} with a valid enum name; emits
     * empty otherwise so the caller's {@code switchIfEmpty} falls through to
     * tenant config. Cannot use {@code defaultIfEmpty(null)} — Reactor forbids
     * null values.
     */
    private Mono<ProrationStrategy> fireProrationRules(UUID tenantId, Claim claim,
                                                       SchemeChangeContext ctx) {
        return tenantRuleLoader.ensureLoaded(tenantId)
                .then(factBuilder.build(claim))
                .flatMap(facts -> {
                    facts.claim().setSchemeChange(ctx);
                    return ruleEvaluationService.evaluateInGroup(
                            tenantId.toString(), AGENDA_GROUP,
                            facts.claim(), facts.member(), facts.provider(), ctx)
                            .flatMap(results -> {
                                ProrationStrategy s = parseRuleStrategy(facts.claim(), results);
                                return s != null ? Mono.just(s) : Mono.empty();
                            });
                });
    }

    private ProrationStrategy parseRuleStrategy(ClaimFact claim, java.util.List<RuleResult> results) {
        String set = claim.getProrationStrategy();
        if (set == null || set.isBlank()) return null;
        try {
            return ProrationStrategy.valueOf(set.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("BENEFIT_PRORATION rule set unknown strategy '{}' — ignoring", set);
            return null;
        }
    }

    private Mono<ProrationStrategy> loadTenantConfig(UUID tenantId, String changeKind) {
        return databaseClient.sql("""
                SELECT default_strategy, upgrade_strategy, downgrade_strategy, currency_strategy
                  FROM public.tenant_proration_config
                 WHERE tenant_id = :tenantId
                """)
                .bind("tenantId", tenantId)
                .map(row -> {
                    String def = asString(row.get("default_strategy"));
                    if ("HYBRID_BY_DIRECTION".equals(def)) {
                        String directional = switch (changeKind == null ? "" : changeKind.toUpperCase()) {
                            case "UPGRADE"         -> asString(row.get("upgrade_strategy"));
                            case "DOWNGRADE"       -> asString(row.get("downgrade_strategy"));
                            case "CURRENCY_CHANGE" -> asString(row.get("currency_strategy"));
                            default                -> null;
                        };
                        return safeStrategy(directional != null ? directional : def);
                    }
                    return safeStrategy(def);
                })
                .one();
    }

    private ProrationDecision applyStrategy(ProrationStrategy strategy, SchemeChangeContext ctx,
                                            BigDecimal newAnnualLimit, String note) {
        BigDecimal effective = strategy.effectiveLimit(ctx, newAnnualLimit);
        return new ProrationDecision(strategy.name(), effective, ctx.totalConsumed(), note);
    }

    private static ProrationStrategy safeStrategy(String s) {
        if (s == null) return ProrationStrategy.NONE;
        try { return ProrationStrategy.valueOf(s.toUpperCase()); }
        catch (IllegalArgumentException e) { return ProrationStrategy.NONE; }
    }

    private static String asString(Object o) { return o == null ? null : o.toString(); }

    private static BigDecimal asDecimal(Object o) {
        if (o == null) return BigDecimal.ZERO;
        if (o instanceof BigDecimal bd) return bd;
        return new BigDecimal(o.toString());
    }

    private record SchemeChangeRow(UUID fromSchemeId, UUID toSchemeId,
                                   LocalDate effectiveDate, String changeKind) {}

    private record PrevBenefit(UUID id, BigDecimal annualLimit, String currencyCode) {}
}
