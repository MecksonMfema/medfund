package com.medfund.claims.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.claims.client.AiServiceClient;
import com.medfund.claims.dto.AdjudicationResult;
import com.medfund.claims.dto.AdjudicationResult.StageResult;
import com.medfund.claims.dto.AiSignals;
import com.medfund.claims.entity.Claim;
import com.medfund.claims.entity.ClaimLine;
import com.medfund.claims.entity.DiagnosisProcedureMapping;
import com.medfund.claims.entity.TariffCode;
import com.medfund.claims.repository.DiagnosisProcedureMappingRepository;
import com.medfund.claims.repository.IcdCodeRepository;
import com.medfund.claims.repository.PreAuthorizationRepository;
import com.medfund.claims.repository.RejectionReasonRepository;
import com.medfund.claims.repository.TariffCodeRepository;
import com.medfund.claims.repository.TariffModifierRepository;
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
                                AdjudicationDecisionEngine decisionEngine) {
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
    }

    public Mono<AdjudicationResult> execute(Claim claim, List<ClaimLine> lines) {
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
            .flatMap(results -> checkBenefitLimits(claim).map(s3 -> {
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
            .flatMap(results -> evaluateTenantRules(claim).map(s7 -> {
                results.add(s7);
                return results;
            }));

        Mono<AiSignals> aiMono = aiServiceClient.evaluate(claim, lines)
                .defaultIfEmpty(AiSignals.empty());

        return Mono.zip(stagesMono, aiMono)
                .map(tuple -> decisionEngine.decide(claim, tuple.getT1(), tuple.getT2()));
    }

    // ---- Stage 1: Eligibility ----

    private Mono<StageResult> checkEligibility(Claim claim) {
        if (claim.getMemberId() == null || claim.getProviderId() == null || claim.getSchemeId() == null) {
            var missing = new ArrayList<String>();
            if (claim.getMemberId() == null) missing.add("memberId");
            if (claim.getProviderId() == null) missing.add("providerId");
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

    private Mono<StageResult> checkBenefitLimits(Claim claim) {
        if (claim.getBenefitId() == null) {
            // No specific benefit — check against overall scheme limit
            return checkOverallBenefitLimit(claim);
        }

        // Check specific benefit limit
        Mono<BigDecimal> benefitLimitMono = databaseClient
            .sql("SELECT annual_limit FROM scheme_benefits WHERE id = :benefitId")
            .bind("benefitId", claim.getBenefitId())
            .fetch().one()
            .map(row -> {
                Object limit = row.get("annual_limit");
                return limit != null ? new BigDecimal(limit.toString()) : BigDecimal.ZERO;
            })
            .defaultIfEmpty(BigDecimal.ZERO);

        // Sum already approved claims for this member + benefit this year
        Mono<BigDecimal> usedYTDMono = databaseClient
            .sql("SELECT COALESCE(SUM(approved_amount), 0) as used FROM claims " +
                 "WHERE member_id = :memberId AND benefit_id = :benefitId " +
                 "AND status IN ('ADJUDICATED', 'COMMITTED', 'PAID') " +
                 "AND EXTRACT(YEAR FROM service_date) = EXTRACT(YEAR FROM CURRENT_DATE)")
            .bind("memberId", claim.getMemberId())
            .bind("benefitId", claim.getBenefitId())
            .fetch().one()
            .map(row -> new BigDecimal(row.get("used").toString()))
            .defaultIfEmpty(BigDecimal.ZERO);

        return Mono.zip(benefitLimitMono, usedYTDMono)
            .map(tuple -> {
                BigDecimal limit = tuple.getT1();
                BigDecimal used = tuple.getT2();

                if (limit.compareTo(BigDecimal.ZERO) == 0) {
                    return new StageResult("BenefitLimits", true,
                        "No annual limit set for this benefit — passed");
                }

                BigDecimal remaining = limit.subtract(used);
                BigDecimal claimed = claim.getClaimedAmount();

                if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                    return new StageResult("BenefitLimits", false,
                        "R03: Benefit limit exhausted (limit: " + limit
                        + ", used: " + used + ", remaining: 0)");
                }

                if (claimed.compareTo(remaining) > 0) {
                    return new StageResult("BenefitLimits", false,
                        "R03: Claimed amount " + claimed + " exceeds remaining benefit balance "
                        + remaining + " (limit: " + limit + ", used YTD: " + used + ")");
                }

                return new StageResult("BenefitLimits", true,
                    "Benefit limit check passed (claimed: " + claimed
                    + ", remaining: " + remaining + " of " + limit + ")");
            });
    }

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

    // ---- Stage 4: Pre-Authorization ----

    private Mono<StageResult> checkPreAuth(Claim claim, List<ClaimLine> lines) {
        return Flux.fromIterable(lines)
            .flatMap(line -> tariffCodeRepository.findByCode(line.getTariffCode())
                .flatMap(tariff -> {
                    if (Boolean.TRUE.equals(tariff.getRequiresPreAuth())) {
                        return preAuthorizationRepository
                            .findByMemberIdAndTariffCodeAndStatus(claim.getMemberId(), tariff.getCode(), "APPROVED")
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
        return Mono.deferContextual(ctx -> {
            String tenant = TenantContext.get(ctx);
            UUID tenantId = parseTenant(tenant);
            if (tenantId == null) {
                return Mono.just(new StageResult("TenantRules", true,
                        "No tenant context — skipping tenant rules"));
            }
            return tenantRuleLoader.ensureLoaded(tenantId)
                .then(factBuilder.build(claim))
                .flatMap(facts -> ruleEvaluationService
                    .evaluateClaim(tenantId.toString(), facts.claim(), facts.member(), facts.provider())
                    .map(this::summariseRuleResults));
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
