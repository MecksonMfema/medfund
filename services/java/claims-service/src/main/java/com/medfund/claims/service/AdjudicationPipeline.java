package com.medfund.claims.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.claims.client.AiServiceClient;
import com.medfund.claims.costshare.AppliedRuleAction;
import com.medfund.claims.dto.AdjudicationResult;
import com.medfund.claims.dto.AdjudicationResult.StageResult;
import com.medfund.claims.dto.AiSignals;
import com.medfund.claims.entity.Claim;
import com.medfund.claims.entity.ClaimLine;
import com.medfund.claims.entity.DiagnosisProcedureMapping;
import com.medfund.claims.entity.PreAuthorization;
import com.medfund.claims.entity.TariffCode;
import com.medfund.claims.repository.DiagnosisProcedureMappingRepository;
import com.medfund.claims.repository.IcdCodeRepository;
import com.medfund.claims.repository.PreAuthorizationRepository;
import com.medfund.claims.repository.RejectionReasonRepository;
import com.medfund.claims.repository.TariffCodeRepository;
import com.medfund.claims.repository.TariffModifierRepository;
import com.medfund.rules.fact.ClaimDetailFact;
import com.medfund.rules.fact.ClaimFact;
import com.medfund.rules.fact.MemberFact;
import com.medfund.rules.fact.ProviderFact;
import com.medfund.rules.fact.RuleResult;
import com.medfund.rules.service.RuleEvaluationService;
import com.medfund.rules.service.TenantRuleLoader;
import com.medfund.shared.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Adjudication pipeline for claims processing.
 *
 * <p>The first six stages are platform defaults that every tenant inherits:
 * <ol>
 *   <li>Eligibility — verifies member is active, provider registered, scheme valid</li>
 *   <li>Waiting Period — checks member enrollment date against scheme waiting period rules</li>
 *   <li>Benefit Limits — checks claimed amount against member's remaining benefit balance</li>
 *   <li>Pre-Authorization — checks if required pre-auths exist and are valid</li>
 *   <li>Tariff Pricing — validates tariff codes and price limits</li>
 *   <li>Clinical Validation — checks diagnosis-procedure mappings</li>
 * </ol>
 *
 * <p>Stage 7 — <b>Tenant Rules</b> — runs whatever the tenant has authored
 * in the rules engine on top of the platform defaults. REJECT outcomes
 * surface as a stage failure; FLAG_FOR_REVIEW / WARN / APPLY_COPAY surface
 * as informational details so the operator can see what fired. Tenants
 * who haven't written any rules see no behaviour change.
 */
@Service
public class AdjudicationPipeline {

    private static final Logger log = LoggerFactory.getLogger(AdjudicationPipeline.class);

    private final TariffCodeRepository tariffCodeRepository;
    private final TariffModifierRepository tariffModifierRepository;
    private final IcdCodeRepository icdCodeRepository;
    private final DiagnosisProcedureMappingRepository diagnosisProcedureMappingRepository;
    private final PreAuthorizationRepository preAuthorizationRepository;
    private final RejectionReasonRepository rejectionReasonRepository;
    private final RuleEvaluationService ruleEvaluationService;
    private final TenantRuleLoader tenantRuleLoader;
    private final ClaimFactBuilder factBuilder;
    private final DatabaseClient databaseClient;
    private final ObjectMapper objectMapper;
    private final AiServiceClient aiServiceClient;
    private final AdjudicationDecisionEngine decisionEngine;
    private final ProrationService prorationService;

    public AdjudicationPipeline(TariffCodeRepository tariffCodeRepository,
                                TariffModifierRepository tariffModifierRepository,
                                IcdCodeRepository icdCodeRepository,
                                DiagnosisProcedureMappingRepository diagnosisProcedureMappingRepository,
                                PreAuthorizationRepository preAuthorizationRepository,
                                RejectionReasonRepository rejectionReasonRepository,
                                RuleEvaluationService ruleEvaluationService,
                                TenantRuleLoader tenantRuleLoader,
                                ClaimFactBuilder factBuilder,
                                DatabaseClient databaseClient,
                                ObjectMapper objectMapper,
                                AiServiceClient aiServiceClient,
                                AdjudicationDecisionEngine decisionEngine,
                                ProrationService prorationService) {
        this.tariffCodeRepository = tariffCodeRepository;
        this.tariffModifierRepository = tariffModifierRepository;
        this.icdCodeRepository = icdCodeRepository;
        this.diagnosisProcedureMappingRepository = diagnosisProcedureMappingRepository;
        this.preAuthorizationRepository = preAuthorizationRepository;
        this.rejectionReasonRepository = rejectionReasonRepository;
        this.ruleEvaluationService = ruleEvaluationService;
        this.tenantRuleLoader = tenantRuleLoader;
        this.factBuilder = factBuilder;
        this.databaseClient = databaseClient;
        this.objectMapper = objectMapper;
        this.aiServiceClient = aiServiceClient;
        this.decisionEngine = decisionEngine;
        this.prorationService = prorationService;
    }

    public Mono<AdjudicationResult> execute(Claim claim, List<ClaimLine> lines) {
        return evaluate(claim, lines)
                .flatMap(eval -> decisionEngine.decide(
                        claim, lines,
                        eval.stages(), eval.aiSignals(),
                        eval.ruleAdjustedTotal(), eval.ruleActions()));
    }

    /**
     * Read-only evaluation of a transient claim shape — same stages, same rule
     * fires, same AI call as {@link #execute(Claim, List)}, but stops short of
     * the decision matrix and skips the sync Kafka publish + DB persistence
     * the caller (ClaimService) layers around it. Used by
     * {@link EligibilityQuoteService} to answer point-of-service eligibility
     * quotes without leaving a row in {@code claims}.
     *
     * <p>The returned {@link DryRunResult} carries the four intermediates a
     * caller needs to reconstruct the same numbers the real pipeline would
     * produce: stage results (for coverage classification), AI signals,
     * {@code ruleAdjustedTotal} (post-modifier total to hand the calculator),
     * and the raw {@link AppliedRuleAction} list (for APPLY_COPAY overrides).
     *
     * <p>Note: {@link #runModifierRules} mutates the supplied {@code lines}'
     * approvedAmount in place. That's fine for a dry-run over a transient
     * list built by {@code EligibilityQuoteService} — those objects live only
     * for the duration of the request. Do not pass a persisted line list.
     */
    public Mono<DryRunResult> dryRun(Claim claim, List<ClaimLine> lines) {
        return evaluate(claim, lines);
    }

    /**
     * Shared body of {@link #execute} and {@link #dryRun}. Returns the four
     * intermediates every downstream branch needs. All queries here are
     * SELECTs against the tenant search-path — no mutation, no Kafka publish.
     */
    private Mono<DryRunResult> evaluate(Claim claim, List<ClaimLine> lines) {
        // Snapshot tenant-rule results so the calculator can consume APPLY_COPAY
        // overrides (Phase 2, G4). Cached so both the stage builder and the
        // decision path resolve the same evaluation, not a double-fire.
        Mono<TenantRuleBundle> tenantRulesWithResultsMono = evaluateTenantRulesWithResults(claim).cache();

        // Run the deterministic stages sequentially (each may depend on the
        // claim's enrichment from the prior step). Run the AI evaluation in
        // parallel — it doesn't depend on stage outcomes and we don't want
        // to serialize an external network call behind every DB hit.
        Mono<List<StageResult>> stagesMono = checkEligibility(claim)
            .flatMap(s1 -> checkWaitingPeriod(claim).map(s2 -> {
                var results = new ArrayList<StageResult>();
                results.add(s1);
                results.add(s2);
                return results;
            }))
            .flatMap(results -> checkBenefitLimits(claim, lines).map(s3 -> {
                results.add(s3);
                return results;
            }))
            .flatMap(results -> checkPreAuth(claim, lines).map(s4 -> {
                results.add(s4);
                return results;
            }))
            .flatMap(results -> validateTariffPricing(claim, lines).map(s5 -> {
                results.add(s5);
                return results;
            }))
            .flatMap(results -> validateClinical(claim, lines).map(s6 -> {
                results.add(s6);
                return results;
            }))
            .flatMap(results -> tenantRulesWithResultsMono.map(bundle -> {
                results.add(bundle.stage());
                return results;
            }));

        Mono<AiSignals> aiMono = aiServiceClient.evaluate(claim, lines)
                .defaultIfEmpty(AiSignals.empty());

        // Fire MODIFIER_ADJUSTMENT rules over the per-line facts. On return
        // each ClaimDetailFact.approvedAmount reflects the tenant-rule outcome
        // (or the seeded billedAmount when no rule matched). We mutate the
        // input `lines` in place so the caller sees the adjusted per-line
        // amounts, then hand the summed total to decisionEngine.decide as the
        // base for the auto-approve amount.
        Mono<BigDecimal> ruleAdjustedTotalMono = runModifierRules(claim, lines);

        Mono<List<AppliedRuleAction>> ruleActionsMono = tenantRulesWithResultsMono
                .map(bundle -> AppliedRuleAction.fromAll(bundle.results()));

        return Mono.zip(stagesMono, aiMono, ruleAdjustedTotalMono, ruleActionsMono)
                .map(tuple -> new DryRunResult(tuple.getT1(), tuple.getT2(), tuple.getT3(), tuple.getT4()));
    }

    /**
     * Snapshot of a read-only pipeline run. Carries just enough to reconstruct
     * the numbers {@link AdjudicationDecisionEngine#decide} + {@link CostShareCalculator}
     * would produce, without committing to a final decision.
     */
    public record DryRunResult(
            List<StageResult> stages,
            AiSignals aiSignals,
            BigDecimal ruleAdjustedTotal,
            List<AppliedRuleAction> ruleActions) {}

    /** Bundle of Stage 7's summarised {@link StageResult} + the raw rule fires
     *  that produced it. The raw list is what {@link CostShareCalculator}
     *  needs to detect APPLY_COPAY overrides; the summarised stage is what
     *  the decision matrix + operator UI consume unchanged. */
    private record TenantRuleBundle(StageResult stage, List<RuleResult> results) {}

    /**
     * Build per-line {@link ClaimDetailFact}s from the loaded lines, fire the
     * tenant's MODIFIER_ADJUSTMENT rules against them (agenda-gated so nothing
     * else runs), copy each rule-adjusted amount back onto the corresponding
     * {@link ClaimLine#setApprovedAmount(BigDecimal)}, and return the summed
     * total.
     *
     * <p>Zero-line claims and tenants without any MODIFIER_ADJUSTMENT rules
     * loaded both short-circuit to the raw claimed-amount total, so pipeline
     * behaviour with no modifier rules is identical to what it was before.
     */
    private Mono<BigDecimal> runModifierRules(Claim claim, List<ClaimLine> lines) {
        if (lines == null || lines.isEmpty()) {
            BigDecimal fallback = claim.getClaimedAmount() != null
                    ? claim.getClaimedAmount() : BigDecimal.ZERO;
            return Mono.just(fallback);
        }
        List<ClaimDetailFact> details = ClaimFactBuilder.toDetailFacts(lines);
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            return factBuilder.build(claim)
                    .flatMap(facts -> {
                        // Pin the fact objects Drools will mutate to the same
                        // list we'll read from — see ClaimService.runModifierRules
                        // for the same reference-matching contract.
                        facts.claim().setDetails(details);
                        return ruleEvaluationService.evaluateModifiers(
                                        tenantId, facts.claim(), facts.member(), facts.provider(), details)
                                .thenReturn(details);
                    });
        }).map(list -> {
            BigDecimal total = BigDecimal.ZERO;
            for (int i = 0; i < lines.size() && i < list.size(); i++) {
                BigDecimal adjusted = list.get(i).getApprovedAmount();
                if (adjusted != null) {
                    lines.get(i).setApprovedAmount(adjusted);
                    total = total.add(adjusted);
                }
            }
            return total;
        });
    }

    // ---- Stage 1: Eligibility ----

    private Mono<StageResult> checkEligibility(Claim claim) {
        // The provider is never a hard requirement at the pipeline gate:
        // every line either accepts a member-reimbursement claim (no
        // provider) or forbids providers entirely (LIFE / DISABILITY —
        // and those were caught at capture time). Only memberId and
        // schemeId are structurally load-bearing here.
        if (claim.getMemberId() == null || claim.getSchemeId() == null) {
            var missing = new ArrayList<String>();
            if (claim.getMemberId() == null) missing.add("memberId");
            if (claim.getSchemeId() == null) missing.add("schemeId");
            return Mono.just(new StageResult("Eligibility", false,
                "R01: Eligibility failed — missing " + String.join(", ", missing)));
        }

        // Check member is active
        return databaseClient.sql("SELECT status, enrollment_date FROM members WHERE id = :id")
            .bind("id", claim.getMemberId())
            .fetch().one()
            .map(row -> {
                String memberStatus = (String) row.get("status");
                if (!"active".equalsIgnoreCase(memberStatus) && !"enrolled".equalsIgnoreCase(memberStatus)) {
                    return new StageResult("Eligibility", false,
                        "R01: Member is not active (status: " + memberStatus + ")");
                }
                return new StageResult("Eligibility", true,
                    "Eligibility passed: member active, provider and scheme present");
            })
            .defaultIfEmpty(new StageResult("Eligibility", false,
                "R01: Member not found: " + claim.getMemberId()));
    }

    // ---- Stage 2: Waiting Period ----

    private Mono<StageResult> checkWaitingPeriod(Claim claim) {
        // Look up member enrollment date and scheme waiting period rules
        Mono<LocalDate> enrollmentDateMono = databaseClient
            .sql("SELECT enrollment_date FROM members WHERE id = :id")
            .bind("id", claim.getMemberId())
            .fetch().one()
            .map(row -> (LocalDate) row.get("enrollment_date"));

        Mono<List<WaitingPeriodInfo>> waitingRulesMono = databaseClient
            .sql("SELECT condition_type, waiting_days FROM waiting_period_rules WHERE scheme_id = :schemeId")
            .bind("schemeId", claim.getSchemeId())
            .fetch().all()
            .map(row -> new WaitingPeriodInfo(
                (String) row.get("condition_type"),
                ((Number) row.get("waiting_days")).intValue()
            ))
            .collectList();

        return Mono.zip(enrollmentDateMono, waitingRulesMono)
            .map(tuple -> {
                LocalDate enrollmentDate = tuple.getT1();
                List<WaitingPeriodInfo> rules = tuple.getT2();

                if (rules.isEmpty()) {
                    return new StageResult("WaitingPeriod", true,
                        "No waiting period rules configured for scheme — passed");
                }

                long daysSinceEnrollment = ChronoUnit.DAYS.between(enrollmentDate, LocalDate.now());
                var failures = new ArrayList<String>();

                for (WaitingPeriodInfo rule : rules) {
                    // "general_illness" applies to all medical claims by default
                    if (daysSinceEnrollment < rule.waitingDays) {
                        failures.add("R02: Waiting period not served for " + rule.conditionType
                            + " (" + rule.waitingDays + " days required, "
                            + daysSinceEnrollment + " days since enrollment)");
                    }
                }

                if (failures.isEmpty()) {
                    return new StageResult("WaitingPeriod", true,
                        "Waiting period satisfied (" + daysSinceEnrollment + " days since enrollment)");
                }

                return new StageResult("WaitingPeriod", false, String.join("; ", failures));
            })
            .defaultIfEmpty(new StageResult("WaitingPeriod", true,
                "Member enrollment date not found — skipping waiting period check"));
    }

    // ---- Stage 3: Benefit Limits ----

    private Mono<StageResult> checkBenefitLimits(Claim claim, List<ClaimLine> lines) {
        // V062: two additional checks layer onto the classic benefit-limit
        // flow — tariff mapping and scheme annual cap. Both run only if
        // the primary benefit check passed. Any failure of the added
        // checks fails the stage overall.
        Mono<StageResult> primary = primaryBenefitCheck(claim);
        return primary.flatMap(primaryResult -> {
            if (!primaryResult.passed()) return Mono.just(primaryResult);
            return validateTariffMappings(claim, lines)
                    .flatMap(mapResult -> !mapResult.passed()
                            ? Mono.just(mapResult)
                            : checkAnnualMemberCap(claim, lines)
                                    .map(capResult -> capResult.passed() ? primaryResult : capResult));
        });
    }

    private Mono<StageResult> primaryBenefitCheck(Claim claim) {
        if (claim.getBenefitId() == null) {
            // No specific benefit — check against overall scheme limit
            return checkOverallBenefitLimit(claim);
        }

        // V051 age gate — reject before the limit check when the benefit
        // carries a min_age/max_age and the member falls outside. The
        // cash_claim_allowed flag is enforced separately by the rules
        // engine (see EligibilityTemplates R51) because the platform does
        // not yet carry a claim-level payout_mode field.
        return checkBenefitAgeRange(claim)
                .flatMap(ageResult -> ageResult.passed()
                        ? benefitLimitCheck(claim)
                        : Mono.just(ageResult));
    }

    /**
     * V063 per-line tariff-category validation. For each line resolve
     * tariff_code → tariff_codes.category_id, then confirm the category
     * either (a) has {@code is_cap_only = TRUE} (pass — cap check
     * enforces the ceiling) or (b) has at least one row in
     * {@code benefit_tariff_categories} linking it to an active
     * scheme_benefit for the claim's scheme.
     *
     * <p>A category with no covering benefit AND {@code is_cap_only = FALSE}
     * fails the stage with {@code R03-TARIFF_UNMAPPED}. Empty tariff
     * strings and tariffs missing from {@code tariff_codes} are
     * skipped (Stage 5 catches tariff-code validity separately).
     */
    private Mono<StageResult> validateTariffMappings(Claim claim, List<ClaimLine> lines) {
        if (lines == null || lines.isEmpty()) {
            return Mono.just(new StageResult("BenefitLimits", true, "No lines to map"));
        }
        return Flux.fromIterable(lines)
                .flatMap(line -> {
                    if (line.getTariffCode() == null || line.getTariffCode().isBlank()) {
                        return Mono.just("(no tariff on line — skipped)");
                    }
                    return tariffCodeRepository.findByCode(line.getTariffCode())
                            .flatMap(tariff -> {
                                UUID categoryId = tariff.getCategoryId();
                                if (categoryId == null) {
                                    // Legacy row that pre-dates V063 backfill —
                                    // treat as cap-only rather than reject so
                                    // adjudication makes progress. An operator
                                    // can reclassify the tariff via the admin UI.
                                    return Mono.just("(" + line.getTariffCode()
                                            + " has no category — treated as cap-only)");
                                }
                                return databaseClient.sql("""
                                        SELECT tc.is_cap_only,
                                               EXISTS (
                                                   SELECT 1 FROM benefit_tariff_categories btc
                                                     JOIN scheme_benefits sb
                                                       ON sb.id = btc.scheme_benefit_id
                                                    WHERE btc.tariff_category_id = tc.id
                                                      AND sb.scheme_id = :schemeId
                                                      AND (sb.status IS NULL OR sb.status = 'active')
                                               ) AS mapped_in_scheme
                                          FROM tariff_categories tc
                                         WHERE tc.id = :categoryId
                                         LIMIT 1
                                        """)
                                        .bind("categoryId", categoryId)
                                        .bind("schemeId", claim.getSchemeId())
                                        .fetch().one()
                                        .map(r -> {
                                            boolean capOnly = r.get("is_cap_only") != null
                                                    && Boolean.parseBoolean(r.get("is_cap_only").toString());
                                            boolean mapped = r.get("mapped_in_scheme") != null
                                                    && Boolean.parseBoolean(r.get("mapped_in_scheme").toString());
                                            if (capOnly) {
                                                return "(" + line.getTariffCode() + " → cap-only category)";
                                            }
                                            if (mapped) {
                                                return "(" + line.getTariffCode() + " → mapped to a scheme benefit)";
                                            }
                                            return "R03-TARIFF_UNMAPPED: tariff " + line.getTariffCode()
                                                    + " (category=" + categoryId + ") is not covered by any "
                                                    + "scheme_benefit on scheme " + claim.getSchemeId();
                                        })
                                        .defaultIfEmpty("(" + line.getTariffCode()
                                                + " category row missing — skipped)");
                            })
                            .defaultIfEmpty("(" + line.getTariffCode() + " not in tariff_codes — skipped)");
                })
                .collectList()
                .map(details -> {
                    boolean passed = details.stream().noneMatch(d -> d.startsWith("R03-TARIFF_UNMAPPED"));
                    return new StageResult("BenefitLimits", passed, String.join("; ", details));
                });
    }

    /**
     * V062 scheme annual cap check. Only runs when the scheme has
     * {@code annual_member_cap} set (typically medical-aid style products).
     * Sums the current beneficiary's cap ledger (beneficiary_annual_totals)
     * for the current policy year and rejects when adding the claim's total
     * claimed amount would breach the cap.
     */
    private Mono<StageResult> checkAnnualMemberCap(Claim claim, List<ClaimLine> lines) {
        if (claim.getSchemeId() == null) {
            return Mono.just(new StageResult("BenefitLimits", true, "No scheme — cap skipped"));
        }
        int year = claim.getServiceDate() != null ? claim.getServiceDate().getYear() : LocalDate.now().getYear();
        BigDecimal claimTotal = (lines == null || lines.isEmpty())
                ? (claim.getClaimedAmount() != null ? claim.getClaimedAmount() : BigDecimal.ZERO)
                : lines.stream().map(l -> l.getClaimedAmount() != null ? l.getClaimedAmount() : BigDecimal.ZERO)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
        return databaseClient.sql("""
                SELECT s.annual_member_cap,
                       (SELECT COALESCE(consumed_amount, 0)
                          FROM beneficiary_annual_totals
                         WHERE scheme_id = :schemeId
                           AND member_id = :memberId
                           AND ((:dependantId IS NULL AND dependant_id IS NULL)
                                OR dependant_id = :dependantId)
                           AND policy_year = :year
                        ) AS consumed
                  FROM schemes s
                 WHERE s.id = :schemeId
                """)
                .bind("schemeId", claim.getSchemeId())
                .bind("memberId", claim.getMemberId())
                .bind("dependantId", claim.getDependantId() != null ? claim.getDependantId() : (UUID) null)
                .bind("year", year)
                .fetch().one()
                .map(row -> {
                    Object capObj = row.get("annual_member_cap");
                    if (capObj == null) {
                        return new StageResult("BenefitLimits", true, "Scheme has no annual cap");
                    }
                    BigDecimal cap = new BigDecimal(capObj.toString());
                    BigDecimal consumed = row.get("consumed") != null
                            ? new BigDecimal(row.get("consumed").toString()) : BigDecimal.ZERO;
                    BigDecimal projected = consumed.add(claimTotal);
                    if (projected.compareTo(cap) > 0) {
                        return new StageResult("BenefitLimits", false,
                                "R03-SCHEME_CAP_EXHAUSTED: claim total " + claimTotal
                                + " would push consumed to " + projected + " over annual cap " + cap
                                + " (currently consumed " + consumed + ")");
                    }
                    return new StageResult("BenefitLimits", true,
                            "Annual cap check passed (projected=" + projected + ", cap=" + cap + ")");
                })
                .defaultIfEmpty(new StageResult("BenefitLimits", true, "Scheme row missing — cap skipped"));
    }

    private Mono<StageResult> benefitLimitCheck(Claim claim) {
        // V061: load usage_mode and tracks_member_balances alongside the
        // classic limit/name/currency triple so Stage 3 can branch per
        // product classification instead of assuming running-balance.
        Mono<BenefitLookup> benefitMono = databaseClient
            .sql("""
                    SELECT b.annual_limit, b.name, b.currency_code, b.usage_mode, b.event_limit,
                           s.tracks_member_balances
                      FROM scheme_benefits b
                      JOIN schemes s ON s.id = b.scheme_id
                     WHERE b.id = :benefitId
                    """)
            .bind("benefitId", claim.getBenefitId())
            .fetch().one()
            .map(row -> new BenefitLookup(
                    row.get("annual_limit") != null ? new BigDecimal(row.get("annual_limit").toString()) : BigDecimal.ZERO,
                    row.get("name") != null ? row.get("name").toString() : null,
                    row.get("currency_code") != null ? row.get("currency_code").toString() : null,
                    row.get("usage_mode") != null ? row.get("usage_mode").toString() : "RUNNING_BALANCE",
                    row.get("event_limit") != null ? new BigDecimal(row.get("event_limit").toString()) : null,
                    row.get("tracks_member_balances") != null
                            ? Boolean.parseBoolean(row.get("tracks_member_balances").toString())
                            : Boolean.TRUE))
            .defaultIfEmpty(new BenefitLookup(BigDecimal.ZERO, null, null, "RUNNING_BALANCE", null, Boolean.TRUE));

        return Mono.deferContextual(ctx -> {
            UUID tenantId = parseTenant(TenantContext.get(ctx));
            return benefitMono.flatMap(benefit -> {
                // V061 branch 1: scheme opts out of per-member ledgers entirely
                // (typical for indemnity products — VEHICLE, PROPERTY). Stage 3
                // stays silent so the ledger read isn't consulted.
                if (Boolean.FALSE.equals(benefit.tracksMemberBalances())) {
                    return Mono.just(new StageResult("BenefitLimits", true,
                            "Scheme opts out of per-member balance tracking"));
                }
                // V061 branch 2: benefit-level opt-out.
                if ("NO_TRACKING".equals(benefit.usageMode())) {
                    return Mono.just(new StageResult("BenefitLimits", true,
                            "Benefit usage_mode=NO_TRACKING — passed"));
                }
                // V061 branch 3: one-time benefits (per-beneficiary or per-period).
                // ONE_TIME_PER_BENEFICIARY uses policy_year=0 sentinel so it
                // never rolls over. ONE_TIME_PER_PERIOD uses the current year.
                if ("ONE_TIME_PER_BENEFICIARY".equals(benefit.usageMode())
                        || "ONE_TIME_PER_PERIOD".equals(benefit.usageMode())) {
                    return checkOneTimeExhaustion(claim, benefit.usageMode());
                }
                // V061 branch 4: per-event counter — reject when consumed_count
                // has hit event_limit even if the amount cap is untouched.
                if ("PER_EVENT_COUNTER".equals(benefit.usageMode())) {
                    return checkPerEventCounter(claim, benefit)
                            .flatMap(res -> res.passed()
                                    ? runningBalanceCheck(tenantId, claim, benefit)
                                    : Mono.just(res));
                }
                // Default: RUNNING_BALANCE — existing ProrationService flow.
                return runningBalanceCheck(tenantId, claim, benefit);
            });
        });
    }

    /** RUNNING_BALANCE evaluation — kept as its own method so the per-event
     *  counter branch can chain into it after passing the count check. */
    private Mono<StageResult> runningBalanceCheck(UUID tenantId, Claim claim, BenefitLookup benefit) {
        if (benefit.annualLimit().compareTo(BigDecimal.ZERO) == 0) {
            return Mono.just(new StageResult("BenefitLimits", true,
                    "No annual limit set for this benefit — passed"));
        }
        return prorationService.resolveEffectiveLimit(
                tenantId, claim,
                benefit.annualLimit(), benefit.name(), benefit.currencyCode())
            .map(decision -> evaluateWithDecision(claim, benefit.annualLimit(), decision));
    }

    /**
     * V061 one-time exhaustion check. Uses policy_year=0 sentinel for
     * ONE_TIME_PER_BENEFICIARY (lifetime); current year for ONE_TIME_PER_PERIOD.
     * Reject when consumed_count > 0 — a single successful claim exhausts.
     */
    private Mono<StageResult> checkOneTimeExhaustion(Claim claim, String usageMode) {
        int year = "ONE_TIME_PER_BENEFICIARY".equals(usageMode) ? 0 : java.time.LocalDate.now().getYear();
        return databaseClient
                .sql("""
                        SELECT COALESCE(consumed_count, 0) AS cc
                          FROM beneficiary_benefits
                         WHERE member_id = :memberId
                           AND ((:dependantId IS NULL AND dependant_id IS NULL)
                                OR dependant_id = :dependantId)
                           AND benefit_id  = :benefitId
                           AND policy_year = :policyYear
                        """)
                .bind("memberId", claim.getMemberId())
                .bind("dependantId", claim.getDependantId() != null ? claim.getDependantId() : (UUID) null)
                .bind("benefitId", claim.getBenefitId())
                .bind("policyYear", year)
                .fetch().one()
                .map(row -> {
                    int count = row.get("cc") != null ? ((Number) row.get("cc")).intValue() : 0;
                    if (count > 0) {
                        return new StageResult("BenefitLimits", false,
                                "R03-ONE_TIME_EXHAUSTED: " + usageMode + " benefit already used"
                                + " (consumed_count=" + count + ")");
                    }
                    return new StageResult("BenefitLimits", true,
                            "One-time benefit available — passed");
                })
                .defaultIfEmpty(new StageResult("BenefitLimits", true,
                        "No beneficiary ledger row yet — treating as available"));
    }

    /**
     * V061 per-event counter check. Rejects when the beneficiary has already
     * hit the {@code event_limit} count for the current year, independent
     * of remaining amount. Runs BEFORE the running-balance amount check.
     */
    private Mono<StageResult> checkPerEventCounter(Claim claim, BenefitLookup benefit) {
        if (benefit.eventLimit() == null || benefit.eventLimit().signum() <= 0) {
            return Mono.just(new StageResult("BenefitLimits", true,
                    "PER_EVENT_COUNTER benefit has no event_limit set — count skipped"));
        }
        int cap = benefit.eventLimit().intValueExact();
        int year = java.time.LocalDate.now().getYear();
        return databaseClient
                .sql("""
                        SELECT COALESCE(consumed_count, 0) AS cc
                          FROM beneficiary_benefits
                         WHERE member_id = :memberId
                           AND ((:dependantId IS NULL AND dependant_id IS NULL)
                                OR dependant_id = :dependantId)
                           AND benefit_id  = :benefitId
                           AND policy_year = :policyYear
                        """)
                .bind("memberId", claim.getMemberId())
                .bind("dependantId", claim.getDependantId() != null ? claim.getDependantId() : (UUID) null)
                .bind("benefitId", claim.getBenefitId())
                .bind("policyYear", year)
                .fetch().one()
                .map(row -> {
                    int count = row.get("cc") != null ? ((Number) row.get("cc")).intValue() : 0;
                    if (count >= cap) {
                        return new StageResult("BenefitLimits", false,
                                "R03-EVENT_COUNTER_EXHAUSTED: consumed " + count
                                + " of " + cap + " permitted events");
                    }
                    return new StageResult("BenefitLimits", true,
                            "Event counter " + count + "/" + cap + " — passed");
                })
                .defaultIfEmpty(new StageResult("BenefitLimits", true,
                        "No beneficiary ledger row yet — event counter starts at 0"));
    }

    private StageResult evaluateWithDecision(Claim claim, BigDecimal rawLimit, ProrationDecision decision) {
        BigDecimal effective = decision.effectiveLimit();
        BigDecimal used = decision.totalConsumed();
        BigDecimal remaining = effective.subtract(used);
        BigDecimal claimed = claim.getClaimedAmount();
        String rejectCode = "R03-" + decision.strategy();

        if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
            return new StageResult("BenefitLimits", false,
                    rejectCode + ": Benefit limit exhausted (rawLimit=" + rawLimit
                    + ", effective=" + effective + ", used=" + used
                    + ", remaining=0; " + decision.note() + ")");
        }

        if (claimed.compareTo(remaining) > 0) {
            return new StageResult("BenefitLimits", false,
                    rejectCode + ": Claimed amount " + claimed + " exceeds remaining benefit balance "
                    + remaining + " (rawLimit=" + rawLimit + ", effective=" + effective
                    + ", used YTD=" + used + "; " + decision.note() + ")");
        }

        return new StageResult("BenefitLimits", true,
                "Benefit limit check passed (claimed=" + claimed + ", remaining=" + remaining
                + " of effective=" + effective + " [rawLimit=" + rawLimit + ", strategy="
                + decision.strategy() + "])");
    }

    private record BenefitLookup(BigDecimal annualLimit, String name, String currencyCode,
                                  String usageMode, BigDecimal eventLimit,
                                  Boolean tracksMemberBalances) {}

    private Mono<StageResult> checkOverallBenefitLimit(Claim claim) {
        // Check total approved claims for this member this year against any scheme-level limit
        return databaseClient
            .sql("SELECT COALESCE(SUM(approved_amount), 0) as used FROM claims " +
                 "WHERE member_id = :memberId AND scheme_id = :schemeId " +
                 "AND status IN ('ADJUDICATED', 'COMMITTED', 'PAID') " +
                 "AND EXTRACT(YEAR FROM service_date) = EXTRACT(YEAR FROM CURRENT_DATE)")
            .bind("memberId", claim.getMemberId())
            .bind("schemeId", claim.getSchemeId())
            .fetch().one()
            .map(row -> {
                BigDecimal used = new BigDecimal(row.get("used").toString());
                return new StageResult("BenefitLimits", true,
                    "No specific benefit limit — total approved YTD: " + used
                    + " (overall limit check deferred to rules engine)");
            })
            .defaultIfEmpty(new StageResult("BenefitLimits", true,
                "No benefit usage data found — passed"));
    }

    /**
     * V051 benefit-age gate. When the referenced benefit sets min_age or
     * max_age and the member's age at claim time falls outside the range,
     * reject with AGE_OUT_OF_RANGE. Passes cleanly (null-safe) when the
     * benefit doesn't set either bound, or when member DoB is missing.
     */
    private Mono<StageResult> checkBenefitAgeRange(Claim claim) {
        if (claim.getBenefitId() == null || claim.getMemberId() == null) {
            return Mono.just(new StageResult("BenefitLimits", true,
                    "Age gate skipped — missing benefit or member reference"));
        }
        return databaseClient
                .sql("""
                        SELECT b.min_age, b.max_age, m.date_of_birth
                          FROM scheme_benefits b, members m
                         WHERE b.id = :benefitId AND m.id = :memberId
                        """)
                .bind("benefitId", claim.getBenefitId())
                .bind("memberId", claim.getMemberId())
                .fetch().one()
                .map(row -> {
                    Object minObj = row.get("min_age");
                    Object maxObj = row.get("max_age");
                    Object dobObj = row.get("date_of_birth");
                    Short minAge = minObj == null ? null : ((Number) minObj).shortValue();
                    Short maxAge = maxObj == null ? null : ((Number) maxObj).shortValue();
                    java.time.LocalDate dob = dobObj instanceof java.time.LocalDate d ? d : null;
                    if ((minAge == null && maxAge == null) || dob == null) {
                        return new StageResult("BenefitLimits", true,
                                "No benefit age gate to enforce");
                    }
                    // Age at service date, not today — preserves fairness on
                    // late-submitted claims where the member has since aged.
                    java.time.LocalDate asOf = claim.getServiceDate() != null
                            ? claim.getServiceDate() : java.time.LocalDate.now();
                    int age = java.time.Period.between(dob, asOf).getYears();
                    if (minAge != null && age < minAge) {
                        return new StageResult("BenefitLimits", false,
                                "AGE_OUT_OF_RANGE: member age " + age
                                        + " below benefit minimum " + minAge);
                    }
                    if (maxAge != null && age > maxAge) {
                        return new StageResult("BenefitLimits", false,
                                "AGE_OUT_OF_RANGE: member age " + age
                                        + " above benefit maximum " + maxAge);
                    }
                    return new StageResult("BenefitLimits", true,
                            "Benefit age gate passed (age " + age + ")");
                })
                .defaultIfEmpty(new StageResult("BenefitLimits", true,
                        "Benefit or member row missing — age gate skipped"));
    }

    // ---- Stage 4: Pre-Authorization ----

    private Mono<StageResult> checkPreAuth(Claim claim, List<ClaimLine> lines) {
        return Flux.fromIterable(lines)
            .flatMap(line -> tariffCodeRepository.findByCode(line.getTariffCode())
                .flatMap(tariff -> {
                    if (Boolean.TRUE.equals(tariff.getRequiresPreAuth())) {
                        // Dependant claims resolve against the dependant's pre-auth;
                        // member claims resolve against the member's (dependant_id
                        // IS NULL) so a member and a dependant can each hold their
                        // own auth for the same code without one shadowing the other.
                        Mono<PreAuthorization> lookup = claim.getDependantId() != null
                            ? preAuthorizationRepository
                                .findByDependantIdAndTariffCodeAndStatus(
                                    claim.getDependantId(), tariff.getCode(), "APPROVED")
                            : preAuthorizationRepository
                                .findByMemberIdAndTariffCodeAndStatus(
                                    claim.getMemberId(), tariff.getCode(), "APPROVED");
                        return lookup
                            .map(preAuth -> {
                                boolean valid = preAuth.getExpiryDate() != null
                                    && !preAuth.getExpiryDate().isBefore(LocalDate.now());
                                return valid
                                    ? "Pre-auth valid for " + tariff.getCode()
                                    : "R05: Pre-auth expired for " + tariff.getCode();
                            })
                            .defaultIfEmpty("R04: Pre-auth required but not found for " + tariff.getCode());
                    }
                    return Mono.just("No pre-auth required for " + tariff.getCode());
                })
                .defaultIfEmpty("Tariff code " + line.getTariffCode() + " not found — skipping pre-auth check"))
            .collectList()
            .map(details -> {
                boolean passed = details.stream().noneMatch(d ->
                    d.startsWith("R04:") || d.startsWith("R05:"));
                return new StageResult("PreAuthorization", passed, String.join("; ", details));
            });
    }

    // ---- Stage 5: Tariff Pricing Validation ----

    private Mono<StageResult> validateTariffPricing(Claim claim, List<ClaimLine> lines) {
        return Flux.fromIterable(lines)
            .flatMap(line -> tariffCodeRepository.findByCode(line.getTariffCode())
                .map(tariff -> validateLinePrice(line, tariff))
                .defaultIfEmpty("R06: Tariff code not found: " + line.getTariffCode()))
            .collectList()
            .map(details -> {
                boolean passed = details.stream().noneMatch(d -> d.startsWith("R06:") || d.startsWith("R07:"));
                return new StageResult("TariffPricing", passed, String.join("; ", details));
            });
    }

    private String validateLinePrice(ClaimLine line, TariffCode tariff) {
        BigDecimal maxAllowed = tariff.getUnitPrice().multiply(BigDecimal.valueOf(line.getQuantity()));
        if (line.getClaimedAmount().compareTo(maxAllowed) > 0) {
            BigDecimal excess = line.getClaimedAmount().subtract(maxAllowed);
            return "R07: Line " + line.getTariffCode() + " claimed " + line.getClaimedAmount()
                + " exceeds tariff limit " + maxAllowed + " by " + excess;
        }
        return "Line " + line.getTariffCode() + " within tariff limit (" + line.getClaimedAmount()
            + " <= " + maxAllowed + ")";
    }

    // ---- Stage 6: Clinical Validation ----

    private Mono<StageResult> validateClinical(Claim claim, List<ClaimLine> lines) {
        List<String> diagnosisCodes = parseDiagnosisCodes(claim.getDiagnosisCodes());
        if (diagnosisCodes.isEmpty()) {
            return Mono.just(new StageResult("ClinicalValidation", true,
                "No diagnosis codes to validate"));
        }

        return Flux.fromIterable(diagnosisCodes)
            .flatMap(diagCode -> Flux.fromIterable(lines)
                .flatMap(line -> diagnosisProcedureMappingRepository
                    .findByIcdCodeAndTariffCode(diagCode, line.getTariffCode())
                    .map(mapping -> validateMapping(diagCode, line.getTariffCode(), mapping))
                    .defaultIfEmpty("No mapping found for " + diagCode + " + " + line.getTariffCode() + " — allowed")))
            .collectList()
            .map(details -> {
                boolean hasInvalid = details.stream().anyMatch(d -> d.startsWith("R09:"));
                return new StageResult("ClinicalValidation", !hasInvalid, String.join("; ", details));
            });
    }

    private String validateMapping(String diagCode, String tariffCode, DiagnosisProcedureMapping mapping) {
        if ("INVALID".equalsIgnoreCase(mapping.getValidity())) {
            return "R09: Diagnosis " + diagCode + " + procedure " + tariffCode + " is INVALID: "
                + (mapping.getNotes() != null ? mapping.getNotes() : "diagnosis-procedure mismatch");
        }
        return "Diagnosis " + diagCode + " + procedure " + tariffCode + " is " + mapping.getValidity();
    }

    private List<String> parseDiagnosisCodes(String diagnosisCodesJson) {
        if (diagnosisCodesJson == null || diagnosisCodesJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(diagnosisCodesJson, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse diagnosis codes JSON: {}", diagnosisCodesJson, e);
            return List.of();
        }
    }

    // ---- Stage 7: Tenant Rules ----

    /**
     * Run the tenant's rule set against the claim. Rules are loaded lazily —
     * the first claim for a tenant after this service starts triggers a load
     * from {@code public.tenant_rules}; subsequent claims reuse the cached
     * KieContainer until a {@code medfund.rules.updated} event invalidates it.
     *
     * <p>Tenants who haven't authored any rules see this stage report
     * "No tenant rules configured — passed" with no other side effects.
     */
    private Mono<StageResult> evaluateTenantRules(Claim claim) {
        return evaluateTenantRulesWithResults(claim).map(TenantRuleBundle::stage);
    }

    /**
     * Same shape as {@link #evaluateTenantRules} but keeps the raw {@link RuleResult}
     * list alongside the summarised {@link StageResult}. Phase 2 uses the raw list
     * to detect {@code APPLY_COPAY} fires so {@link CostShareCalculator} can honour
     * rule overrides — the stage string can't be parsed back into structured amounts
     * without ambiguity.
     */
    private Mono<TenantRuleBundle> evaluateTenantRulesWithResults(Claim claim) {
        return Mono.deferContextual(ctx -> {
            String tenant = TenantContext.get(ctx);
            UUID tenantId = parseTenant(tenant);
            if (tenantId == null) {
                return Mono.just(new TenantRuleBundle(new StageResult("TenantRules", true,
                        "No tenant context — skipping tenant rules"), List.of()));
            }
            return tenantRuleLoader.ensureLoaded(tenantId)
                .then(factBuilder.build(claim))
                .flatMap(facts -> ruleEvaluationService
                    .evaluateClaim(tenantId.toString(), facts.claim(), facts.member(), facts.provider())
                    .map(results -> new TenantRuleBundle(summariseRuleResults(results), results)));
        });
    }

    private StageResult summariseRuleResults(List<RuleResult> results) {
        if (results == null || results.isEmpty()) {
            return new StageResult("TenantRules", true, "No tenant rules fired — passed");
        }
        boolean rejected = results.stream().anyMatch(r -> "REJECT".equalsIgnoreCase(r.getType()));
        String details = results.stream()
                .map(r -> {
                    String code = r.getCode() != null ? r.getCode() + ": " : "";
                    return r.getType() + " — " + code + (r.getMessage() != null ? r.getMessage() : "");
                })
                .reduce((a, b) -> a + "; " + b)
                .orElse("");
        return new StageResult("TenantRules", !rejected, details);
    }

    private UUID parseTenant(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return UUID.fromString(raw); } catch (IllegalArgumentException e) { return null; }
    }

    // ---- Helper record ----
    // Decision matrix lives in AdjudicationDecisionEngine — keeping it
    // separate lets the matrix be unit-tested without spinning up the
    // whole pipeline.

    private record WaitingPeriodInfo(String conditionType, int waitingDays) {}
}
