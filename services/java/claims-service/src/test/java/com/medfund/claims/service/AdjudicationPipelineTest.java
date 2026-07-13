package com.medfund.claims.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.claims.client.AiServiceClient;
import com.medfund.claims.dto.AiSignals;
import com.medfund.claims.entity.Claim;
import com.medfund.claims.entity.ClaimLine;
import com.medfund.claims.entity.TariffCode;
import com.medfund.claims.repository.DiagnosisProcedureMappingRepository;
import com.medfund.claims.repository.IcdCodeRepository;
import com.medfund.claims.repository.PreAuthorizationRepository;
import com.medfund.claims.repository.RejectionReasonRepository;
import com.medfund.claims.repository.TariffCodeRepository;
import com.medfund.claims.repository.TariffModifierRepository;
import com.medfund.rules.service.RuleEvaluationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;
import org.mockito.junit.jupiter.MockitoSettings;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.FetchSpec;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdjudicationPipelineTest {

    @Mock private TariffCodeRepository tariffCodeRepository;
    @Mock private TariffModifierRepository tariffModifierRepository;
    @Mock private IcdCodeRepository icdCodeRepository;
    @Mock private DiagnosisProcedureMappingRepository diagnosisProcedureMappingRepository;
    @Mock private PreAuthorizationRepository preAuthorizationRepository;
    @Mock private RejectionReasonRepository rejectionReasonRepository;
    @Mock private RuleEvaluationService ruleEvaluationService;
    @Mock private com.medfund.rules.service.TenantRuleLoader tenantRuleLoader;
    @Mock private ClaimFactBuilder factBuilder;
    @Mock private DatabaseClient databaseClient;
    @Mock private AiServiceClient aiServiceClient;
    @Mock private ProrationService prorationService;

    private AdjudicationPipeline adjudicationPipeline;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        adjudicationPipeline = new AdjudicationPipeline(
                tariffCodeRepository, tariffModifierRepository,
                icdCodeRepository, diagnosisProcedureMappingRepository,
                preAuthorizationRepository, rejectionReasonRepository,
                ruleEvaluationService, tenantRuleLoader, factBuilder,
                databaseClient, new ObjectMapper(),
                aiServiceClient, new AdjudicationDecisionEngine(),
                prorationService
        );

        // Default proration: NONE, effective = benefit raw limit, nothing consumed.
        // Individual tests can override by re-stubbing with specific decisions.
        when(prorationService.resolveEffectiveLimit(any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    java.math.BigDecimal rawLimit = inv.getArgument(2);
                    return Mono.just(new ProrationDecision(
                            com.medfund.rules.model.ProrationStrategy.NONE.name(),
                            rawLimit,
                            java.math.BigDecimal.ZERO,
                            "test default"));
                });

        // Default AI mock: empty signal — equivalent to fail-open behavior in
        // the decision engine. Tests that need to assert AI-influenced paths
        // override this in the test body.
        when(aiServiceClient.evaluate(any(), any())).thenReturn(Mono.just(AiSignals.empty()));

        // Tenant-rules stage defaults: no rules loaded, returns empty result list.
        when(tenantRuleLoader.ensureLoaded(any(java.util.UUID.class))).thenReturn(Mono.empty());
        when(factBuilder.build(any())).thenReturn(Mono.just(
                new ClaimFactBuilder.Facts(
                        new com.medfund.rules.fact.ClaimFact(),
                        new com.medfund.rules.fact.MemberFact(),
                        new com.medfund.rules.fact.ProviderFact())));
        when(ruleEvaluationService.evaluateClaim(anyString(), any(), any(), any()))
                .thenReturn(Mono.just(java.util.List.of()));

        // Default mock: DatabaseClient returns active member enrolled 1 year ago, no waiting rules, no usage
        DatabaseClient.GenericExecuteSpec spec = mock(DatabaseClient.GenericExecuteSpec.class);
        FetchSpec<Map<String, Object>> fetch = mock(FetchSpec.class);

        when(databaseClient.sql(anyString())).thenReturn(spec);
        when(spec.bind(anyString(), any())).thenReturn(spec);
        when(spec.fetch()).thenReturn(fetch);
        when(fetch.one()).thenReturn(Mono.just(Map.of(
            "status", "active",
            "enrollment_date", LocalDate.now().minusDays(365),
            "used", BigDecimal.ZERO
        )));
        when(fetch.all()).thenReturn(Flux.empty());
    }

    @Test
    void execute_allStagesPass_returnsApproved() {
        Claim claim = createTestClaim();
        ClaimLine line = createTestClaimLine(claim.getId(), "TC001");
        when(tariffCodeRepository.findByCode("TC001")).thenReturn(Mono.just(createTestTariffCode("TC001", false)));

        StepVerifier.create(adjudicationPipeline.execute(claim, List.of(line)))
                .assertNext(result -> {
                    assertThat(result.decision()).isEqualTo("APPROVED");
                    assertThat(result.approvedAmount()).isEqualByComparingTo(new BigDecimal("500.00"));
                    assertThat(result.stageResults()).hasSize(7);
                    assertThat(result.stageResults()).allMatch(stage -> stage.passed());
                })
                .verifyComplete();
    }

    @Test
    void execute_preAuthRequired_noPreAuth_returnsRejected() {
        Claim claim = createTestClaim();
        ClaimLine line = createTestClaimLine(claim.getId(), "TC002");
        when(tariffCodeRepository.findByCode("TC002")).thenReturn(Mono.just(createTestTariffCode("TC002", true)));
        when(preAuthorizationRepository.findByMemberIdAndTariffCodeAndStatus(
                eq(claim.getMemberId()), eq("TC002"), eq("APPROVED")))
                .thenReturn(Mono.empty());

        StepVerifier.create(adjudicationPipeline.execute(claim, List.of(line)))
                .assertNext(result -> {
                    assertThat(result.decision()).isEqualTo("REJECTED");
                    assertThat(result.stageResults()).anyMatch(
                            s -> "PreAuthorization".equals(s.stageName()) && !s.passed());
                })
                .verifyComplete();
    }

    @Test
    void execute_dependantClaim_looksUpDependantPreAuth() {
        // Dependant claims must resolve pre-auth against the dependant, not
        // the sponsor — a sponsor's own auth for the same code should not
        // cover a dependant's line, and vice versa. Guard the routing at
        // the pipeline entry point.
        Claim claim = createTestClaim();
        claim.setDependantId(UUID.randomUUID());
        ClaimLine line = createTestClaimLine(claim.getId(), "TC002");
        when(tariffCodeRepository.findByCode("TC002")).thenReturn(Mono.just(createTestTariffCode("TC002", true)));
        when(preAuthorizationRepository.findByDependantIdAndTariffCodeAndStatus(
                eq(claim.getDependantId()), eq("TC002"), eq("APPROVED")))
                .thenReturn(Mono.empty());

        StepVerifier.create(adjudicationPipeline.execute(claim, List.of(line)))
                .assertNext(result -> {
                    assertThat(result.decision()).isEqualTo("REJECTED");
                    assertThat(result.stageResults()).anyMatch(
                            s -> "PreAuthorization".equals(s.stageName()) && !s.passed());
                })
                .verifyComplete();

        verify(preAuthorizationRepository).findByDependantIdAndTariffCodeAndStatus(
                eq(claim.getDependantId()), eq("TC002"), eq("APPROVED"));
        verify(preAuthorizationRepository, never()).findByMemberIdAndTariffCodeAndStatus(
                eq(claim.getMemberId()), eq("TC002"), eq("APPROVED"));
    }

    @Test
    void execute_invalidTariffCode_returnsRejected() {
        Claim claim = createTestClaim();
        ClaimLine line = createTestClaimLine(claim.getId(), "INVALID_CODE");
        when(tariffCodeRepository.findByCode("INVALID_CODE")).thenReturn(Mono.empty());

        StepVerifier.create(adjudicationPipeline.execute(claim, List.of(line)))
                .assertNext(result -> {
                    assertThat(result.decision()).isEqualTo("REJECTED");
                    assertThat(result.stageResults()).anyMatch(
                            s -> "TariffPricing".equals(s.stageName()) && !s.passed());
                })
                .verifyComplete();
    }

    @Test
    @SuppressWarnings("unchecked")
    void execute_memberNotActive_returnsRejected() {
        Claim claim = createTestClaim();
        ClaimLine line = createTestClaimLine(claim.getId(), "TC001");

        // Override mock: member is suspended
        DatabaseClient.GenericExecuteSpec spec = mock(DatabaseClient.GenericExecuteSpec.class);
        FetchSpec<Map<String, Object>> fetch = mock(FetchSpec.class);
        when(databaseClient.sql(anyString())).thenReturn(spec);
        when(spec.bind(anyString(), any())).thenReturn(spec);
        when(spec.fetch()).thenReturn(fetch);
        when(fetch.one()).thenReturn(Mono.just(Map.of(
            "status", "suspended",
            "enrollment_date", LocalDate.now().minusDays(365),
            "used", BigDecimal.ZERO
        )));
        when(fetch.all()).thenReturn(Flux.empty());
        when(tariffCodeRepository.findByCode("TC001")).thenReturn(Mono.just(createTestTariffCode("TC001", false)));

        StepVerifier.create(adjudicationPipeline.execute(claim, List.of(line)))
                .assertNext(result -> {
                    assertThat(result.decision()).isEqualTo("REJECTED");
                    assertThat(result.stageResults().get(0).stageName()).isEqualTo("Eligibility");
                    assertThat(result.stageResults().get(0).passed()).isFalse();
                    assertThat(result.stageResults().get(0).details()).contains("R01");
                })
                .verifyComplete();
    }

    @Test
    @SuppressWarnings("unchecked")
    void execute_waitingPeriodNotServed_returnsManualReview() {
        Claim claim = createTestClaim();
        ClaimLine line = createTestClaimLine(claim.getId(), "TC001");

        // Member enrolled 30 days ago + waiting period rule of 90 days
        DatabaseClient.GenericExecuteSpec spec = mock(DatabaseClient.GenericExecuteSpec.class);
        FetchSpec<Map<String, Object>> fetch = mock(FetchSpec.class);
        when(databaseClient.sql(anyString())).thenReturn(spec);
        when(spec.bind(anyString(), any())).thenReturn(spec);
        when(spec.fetch()).thenReturn(fetch);
        when(fetch.one()).thenReturn(Mono.just(Map.of(
            "status", "active",
            "enrollment_date", LocalDate.now().minusDays(30),
            "used", BigDecimal.ZERO
        )));
        when(fetch.all()).thenReturn(Flux.just(
            Map.<String, Object>of("condition_type", "general_illness", "waiting_days", 90)
        ));
        when(tariffCodeRepository.findByCode("TC001")).thenReturn(Mono.just(createTestTariffCode("TC001", false)));

        StepVerifier.create(adjudicationPipeline.execute(claim, List.of(line)))
                .assertNext(result -> {
                    assertThat(result.decision()).isEqualTo("MANUAL_REVIEW");
                    assertThat(result.stageResults()).anyMatch(
                        s -> "WaitingPeriod".equals(s.stageName()) && !s.passed());
                })
                .verifyComplete();
    }

    @Test
    @SuppressWarnings("unchecked")
    void benefitLimitCheck_delegatesToProrationService_whenBenefitIdPresent() {
        Claim claim = createTestClaim();
        claim.setBenefitId(UUID.randomUUID());
        ClaimLine line = createTestClaimLine(claim.getId(), "TC001");
        when(tariffCodeRepository.findByCode("TC001")).thenReturn(Mono.just(createTestTariffCode("TC001", false)));

        // Return a non-zero annual_limit so the benefit-limit stage does NOT skip; then
        // Proration returns a CALENDAR decision — effective 1500 with 800 already used
        // → remaining 700 → claim of 500 fits → stage passes.
        DatabaseClient.GenericExecuteSpec spec = mock(DatabaseClient.GenericExecuteSpec.class);
        FetchSpec<Map<String, Object>> fetch = mock(FetchSpec.class);
        when(databaseClient.sql(anyString())).thenReturn(spec);
        when(spec.bind(anyString(), any())).thenReturn(spec);
        when(spec.fetch()).thenReturn(fetch);
        when(fetch.one()).thenReturn(Mono.just(Map.<String, Object>of(
            "status", "active",
            "enrollment_date", LocalDate.now().minusDays(365),
            "used", BigDecimal.ZERO,
            "annual_limit", new BigDecimal("3000"),
            "name", "Consultation",
            "currency_code", "USD"
        )));
        when(fetch.all()).thenReturn(Flux.empty());

        when(prorationService.resolveEffectiveLimit(any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(new ProrationDecision(
                        com.medfund.rules.model.ProrationStrategy.CALENDAR.name(),
                        new BigDecimal("1500"),
                        new BigDecimal("800"),
                        "Tenant-config CALENDAR")));

        StepVerifier.create(adjudicationPipeline.execute(claim, List.of(line)))
                .assertNext(result -> {
                    assertThat(result.stageResults()).anyMatch(s ->
                            "BenefitLimits".equals(s.stageName())
                                    && s.passed()
                                    && s.details().contains("strategy=CALENDAR"));
                })
                .verifyComplete();

        // Verify ProrationService was actually called with the raw limit we returned above
        org.mockito.Mockito.verify(prorationService)
                .resolveEffectiveLimit(any(), any(), eq(new BigDecimal("3000")), eq("Consultation"), eq("USD"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void benefitLimitCheck_prorationReturnsExhaustedLimit_stageRejectsWithStrategySuffix() {
        Claim claim = createTestClaim();
        claim.setBenefitId(UUID.randomUUID());
        ClaimLine line = createTestClaimLine(claim.getId(), "TC001");
        when(tariffCodeRepository.findByCode("TC001")).thenReturn(Mono.just(createTestTariffCode("TC001", false)));

        DatabaseClient.GenericExecuteSpec spec = mock(DatabaseClient.GenericExecuteSpec.class);
        FetchSpec<Map<String, Object>> fetch = mock(FetchSpec.class);
        when(databaseClient.sql(anyString())).thenReturn(spec);
        when(spec.bind(anyString(), any())).thenReturn(spec);
        when(spec.fetch()).thenReturn(fetch);
        when(fetch.one()).thenReturn(Mono.just(Map.<String, Object>of(
            "status", "active",
            "enrollment_date", LocalDate.now().minusDays(365),
            "used", BigDecimal.ZERO,
            "annual_limit", new BigDecimal("1000"),
            "name", "Consultation",
            "currency_code", "USD"
        )));
        when(fetch.all()).thenReturn(Flux.empty());

        // DELTA_CREDIT: effective = 1000 − 1000 = 0 → immediate exhaustion
        when(prorationService.resolveEffectiveLimit(any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(new ProrationDecision(
                        com.medfund.rules.model.ProrationStrategy.DELTA_CREDIT.name(),
                        BigDecimal.ZERO,
                        new BigDecimal("1000"),
                        "Tenant-config DELTA_CREDIT")));

        StepVerifier.create(adjudicationPipeline.execute(claim, List.of(line)))
                .assertNext(result -> {
                    // The overall pipeline decision folds in AI + all stages; the stage-level
                    // assertion is what this test guards. The R03 code carries the strategy
                    // name for audit traceability.
                    assertThat(result.stageResults()).anyMatch(s ->
                            "BenefitLimits".equals(s.stageName())
                                    && !s.passed()
                                    && s.details().contains("R03-DELTA_CREDIT"));
                })
                .verifyComplete();
    }

    // ---- V061 usage_mode branches ----

    /**
     * V061 branch — when a benefit is NO_TRACKING, Stage 3 passes without
     * consulting the beneficiary ledger. Guards against a regression where
     * we accidentally start requiring a ledger row for declarative benefits.
     */
    @Test
    @SuppressWarnings("unchecked")
    void benefitLimitCheck_noTrackingUsageMode_passesImmediately() {
        Claim claim = createTestClaim();
        claim.setBenefitId(UUID.randomUUID());
        ClaimLine line = createTestClaimLine(claim.getId(), "TC001");
        when(tariffCodeRepository.findByCode("TC001")).thenReturn(Mono.just(createTestTariffCode("TC001", false)));

        DatabaseClient.GenericExecuteSpec spec = mock(DatabaseClient.GenericExecuteSpec.class);
        FetchSpec<Map<String, Object>> fetch = mock(FetchSpec.class);
        when(databaseClient.sql(anyString())).thenReturn(spec);
        when(spec.bind(anyString(), any())).thenReturn(spec);
        when(spec.fetch()).thenReturn(fetch);
        // The map covers keys queried by every stage: age-range, benefit-lookup,
        // usage-mode, mapping check. NO_TRACKING short-circuits before the
        // proration path even runs.
        Map<String, Object> row = new java.util.HashMap<>();
        row.put("status", "active");
        row.put("enrollment_date", LocalDate.now().minusDays(365));
        row.put("used", BigDecimal.ZERO);
        row.put("annual_limit", new BigDecimal("3000"));
        row.put("name", "Wellness benefit");
        row.put("currency_code", "USD");
        row.put("usage_mode", "NO_TRACKING");
        row.put("tracks_member_balances", true);
        when(fetch.one()).thenReturn(Mono.just(row));
        when(fetch.all()).thenReturn(Flux.empty());

        StepVerifier.create(adjudicationPipeline.execute(claim, List.of(line)))
                .assertNext(result -> {
                    assertThat(result.stageResults()).anyMatch(s ->
                            "BenefitLimits".equals(s.stageName())
                                    && s.passed()
                                    && s.details().contains("NO_TRACKING"));
                })
                .verifyComplete();
    }

    /**
     * V061 branch — a scheme flagged tracks_member_balances=false skips
     * the per-member ledger entirely (typical for indemnity products like
     * VEHICLE / PROPERTY). Stage 3 acknowledges the opt-out rather than
     * silently succeeding — the pass details cite the opt-out so the
     * adjudicator sees why the balance check was skipped.
     */
    @Test
    @SuppressWarnings("unchecked")
    void benefitLimitCheck_schemeOptsOutOfMemberBalances_passesWithOptOutDetails() {
        Claim claim = createTestClaim();
        claim.setBenefitId(UUID.randomUUID());
        ClaimLine line = createTestClaimLine(claim.getId(), "TC001");
        when(tariffCodeRepository.findByCode("TC001")).thenReturn(Mono.just(createTestTariffCode("TC001", false)));

        DatabaseClient.GenericExecuteSpec spec = mock(DatabaseClient.GenericExecuteSpec.class);
        FetchSpec<Map<String, Object>> fetch = mock(FetchSpec.class);
        when(databaseClient.sql(anyString())).thenReturn(spec);
        when(spec.bind(anyString(), any())).thenReturn(spec);
        when(spec.fetch()).thenReturn(fetch);
        Map<String, Object> row = new java.util.HashMap<>();
        row.put("status", "active");
        row.put("enrollment_date", LocalDate.now().minusDays(365));
        row.put("used", BigDecimal.ZERO);
        row.put("annual_limit", new BigDecimal("3000"));
        row.put("name", "Roadside cover");
        row.put("currency_code", "USD");
        row.put("usage_mode", "RUNNING_BALANCE");
        row.put("tracks_member_balances", false);
        when(fetch.one()).thenReturn(Mono.just(row));
        when(fetch.all()).thenReturn(Flux.empty());

        StepVerifier.create(adjudicationPipeline.execute(claim, List.of(line)))
                .assertNext(result -> {
                    assertThat(result.stageResults()).anyMatch(s ->
                            "BenefitLimits".equals(s.stageName())
                                    && s.passed()
                                    && s.details().contains("opts out"));
                })
                .verifyComplete();
    }

    /**
     * V061 branch — a ONE_TIME_PER_BENEFICIARY benefit already used
     * (consumed_count > 0) rejects with R03-ONE_TIME_EXHAUSTED. This is
     * the funeral/life-cover shape.
     */
    @Test
    @SuppressWarnings("unchecked")
    void benefitLimitCheck_oneTimePerBeneficiaryAlreadyConsumed_rejectsR03OneTimeExhausted() {
        Claim claim = createTestClaim();
        claim.setBenefitId(UUID.randomUUID());
        ClaimLine line = createTestClaimLine(claim.getId(), "TC001");
        when(tariffCodeRepository.findByCode("TC001")).thenReturn(Mono.just(createTestTariffCode("TC001", false)));

        DatabaseClient.GenericExecuteSpec spec = mock(DatabaseClient.GenericExecuteSpec.class);
        FetchSpec<Map<String, Object>> fetch = mock(FetchSpec.class);
        when(databaseClient.sql(anyString())).thenReturn(spec);
        when(spec.bind(anyString(), any())).thenReturn(spec);
        when(spec.fetch()).thenReturn(fetch);
        Map<String, Object> row = new java.util.HashMap<>();
        row.put("status", "active");
        row.put("enrollment_date", LocalDate.now().minusDays(365));
        row.put("annual_limit", new BigDecimal("10000"));
        row.put("name", "Funeral");
        row.put("currency_code", "USD");
        row.put("usage_mode", "ONE_TIME_PER_BENEFICIARY");
        row.put("tracks_member_balances", true);
        // consumed_count = 1 → already used → reject
        row.put("cc", 1);
        when(fetch.one()).thenReturn(Mono.just(row));
        when(fetch.all()).thenReturn(Flux.empty());

        StepVerifier.create(adjudicationPipeline.execute(claim, List.of(line)))
                .assertNext(result -> {
                    assertThat(result.stageResults()).anyMatch(s ->
                            "BenefitLimits".equals(s.stageName())
                                    && !s.passed()
                                    && s.details().contains("R03-ONE_TIME_EXHAUSTED"));
                })
                .verifyComplete();
    }

    // ---- V062 tariff-mapping validation ----

    /**
     * V062 — when the tariff category has no covering scheme_benefit
     * AND is not cap-only, Stage 3 rejects with R03-TARIFF_UNMAPPED so
     * the operator can add the missing mapping via the admin UI.
     */
    @Test
    @SuppressWarnings("unchecked")
    void validateTariffMappings_categoryUnmappedForScheme_rejectsR03TariffUnmapped() {
        Claim claim = createTestClaim();
        // benefitId=null takes the checkOverallBenefitLimit path which passes
        // silently, so we can isolate the tariff-mapping check on the line.
        UUID categoryId = UUID.randomUUID();
        var tc = createTestTariffCode("TC001", false);
        tc.setCategoryId(categoryId);
        when(tariffCodeRepository.findByCode("TC001")).thenReturn(Mono.just(tc));

        ClaimLine line = createTestClaimLine(claim.getId(), "TC001");

        DatabaseClient.GenericExecuteSpec spec = mock(DatabaseClient.GenericExecuteSpec.class);
        FetchSpec<Map<String, Object>> fetch = mock(FetchSpec.class);
        when(databaseClient.sql(anyString())).thenReturn(spec);
        when(spec.bind(anyString(), any())).thenReturn(spec);
        when(spec.fetch()).thenReturn(fetch);
        Map<String, Object> row = new java.util.HashMap<>();
        // Eligibility + waiting + overall-limit + cap checks all read from
        // the same mock; put the union of expected keys so each stage
        // extracts what it needs. is_cap_only=false + mapped_in_scheme=false
        // is the unmapped-for-this-scheme case.
        row.put("status", "active");
        row.put("enrollment_date", LocalDate.now().minusDays(365));
        row.put("used", BigDecimal.ZERO);
        row.put("is_cap_only", false);
        row.put("mapped_in_scheme", false);
        when(fetch.one()).thenReturn(Mono.just(row));
        when(fetch.all()).thenReturn(Flux.empty());

        StepVerifier.create(adjudicationPipeline.execute(claim, List.of(line)))
                .assertNext(result -> {
                    assertThat(result.stageResults()).anyMatch(s ->
                            "BenefitLimits".equals(s.stageName())
                                    && !s.passed()
                                    && s.details().contains("R03-TARIFF_UNMAPPED"));
                })
                .verifyComplete();
    }

    // ---- V062 annual cap ----

    /**
     * V062 — when consumed_amount + claim total would push over the
     * scheme's annual_member_cap, Stage 3 rejects with
     * R03-SCHEME_CAP_EXHAUSTED. Runs whether or not the per-benefit
     * check passes.
     */
    @Test
    @SuppressWarnings("unchecked")
    void checkAnnualMemberCap_projectedOverCap_rejectsR03SchemeCapExhausted() {
        Claim claim = createTestClaim();
        // No specific benefitId → primary check goes via checkOverallBenefitLimit
        // which passes silently, isolating the cap-check branch.
        UUID categoryId = UUID.randomUUID();
        var tc = createTestTariffCode("TC001", false);
        tc.setCategoryId(categoryId);
        when(tariffCodeRepository.findByCode("TC001")).thenReturn(Mono.just(tc));

        ClaimLine line = createTestClaimLine(claim.getId(), "TC001");

        DatabaseClient.GenericExecuteSpec spec = mock(DatabaseClient.GenericExecuteSpec.class);
        FetchSpec<Map<String, Object>> fetch = mock(FetchSpec.class);
        when(databaseClient.sql(anyString())).thenReturn(spec);
        when(spec.bind(anyString(), any())).thenReturn(spec);
        when(spec.fetch()).thenReturn(fetch);
        Map<String, Object> row = new java.util.HashMap<>();
        row.put("status", "active");
        row.put("enrollment_date", LocalDate.now().minusDays(365));
        row.put("used", BigDecimal.ZERO);
        // Tariff mapping — cap-only so the mapping check passes.
        row.put("is_cap_only", true);
        row.put("mapped_in_scheme", false);
        // Cap: 1000 already consumed, cap is 1200, claim total is 500 → over.
        row.put("annual_member_cap", new BigDecimal("1200"));
        row.put("consumed", new BigDecimal("1000"));
        when(fetch.one()).thenReturn(Mono.just(row));
        when(fetch.all()).thenReturn(Flux.empty());

        StepVerifier.create(adjudicationPipeline.execute(claim, List.of(line)))
                .assertNext(result -> {
                    assertThat(result.stageResults()).anyMatch(s ->
                            "BenefitLimits".equals(s.stageName())
                                    && !s.passed()
                                    && s.details().contains("R03-SCHEME_CAP_EXHAUSTED"));
                })
                .verifyComplete();
    }

    /**
     * V062 — the cap check is a no-op when the scheme has no cap set
     * (annual_member_cap = null). Regression guard against the check
     * accidentally rejecting single-benefit product lines.
     */
    @Test
    @SuppressWarnings("unchecked")
    void checkAnnualMemberCap_schemeHasNoCap_passes() {
        Claim claim = createTestClaim();
        UUID categoryId = UUID.randomUUID();
        var tc = createTestTariffCode("TC001", false);
        tc.setCategoryId(categoryId);
        when(tariffCodeRepository.findByCode("TC001")).thenReturn(Mono.just(tc));

        ClaimLine line = createTestClaimLine(claim.getId(), "TC001");

        DatabaseClient.GenericExecuteSpec spec = mock(DatabaseClient.GenericExecuteSpec.class);
        FetchSpec<Map<String, Object>> fetch = mock(FetchSpec.class);
        when(databaseClient.sql(anyString())).thenReturn(spec);
        when(spec.bind(anyString(), any())).thenReturn(spec);
        when(spec.fetch()).thenReturn(fetch);
        Map<String, Object> row = new java.util.HashMap<>();
        row.put("status", "active");
        row.put("enrollment_date", LocalDate.now().minusDays(365));
        row.put("used", BigDecimal.ZERO);
        row.put("is_cap_only", true);
        row.put("mapped_in_scheme", false);
        // annual_member_cap absent → cap check acknowledges "no cap".
        when(fetch.one()).thenReturn(Mono.just(row));
        when(fetch.all()).thenReturn(Flux.empty());

        StepVerifier.create(adjudicationPipeline.execute(claim, List.of(line)))
                .assertNext(result -> {
                    assertThat(result.stageResults()).anyMatch(s ->
                            "BenefitLimits".equals(s.stageName()) && s.passed());
                })
                .verifyComplete();
    }

    // ---- Test data ----

    private Claim createTestClaim() {
        var claim = new Claim();
        claim.setId(UUID.randomUUID());
        claim.setClaimNumber("CLM-123456");
        claim.setMemberId(UUID.randomUUID());
        claim.setProviderId(UUID.randomUUID());
        claim.setSchemeId(UUID.randomUUID());
        claim.setClaimType("medical");
        claim.setStatus("VERIFIED");
        claim.setServiceDate(LocalDate.now());
        claim.setClaimedAmount(new BigDecimal("500.00"));
        claim.setCurrencyCode("USD");
        claim.setDiagnosisCodes(null);
        claim.setCreatedAt(Instant.now());
        claim.setUpdatedAt(Instant.now());
        claim.setCreatedBy(UUID.randomUUID());
        claim.setUpdatedBy(UUID.randomUUID());
        return claim;
    }

    private ClaimLine createTestClaimLine(UUID claimId, String tariffCode) {
        var line = new ClaimLine();
        line.setId(UUID.randomUUID());
        line.setClaimId(claimId);
        line.setTariffCode(tariffCode);
        line.setDescription("Test procedure");
        line.setQuantity(1);
        line.setUnitPrice(new BigDecimal("500.00"));
        line.setClaimedAmount(new BigDecimal("500.00"));
        line.setCurrencyCode("USD");
        line.setCreatedAt(Instant.now());
        return line;
    }

    private TariffCode createTestTariffCode(String code, boolean requiresPreAuth) {
        var tc = new TariffCode();
        tc.setId(UUID.randomUUID());
        tc.setScheduleId(UUID.randomUUID());
        tc.setCode(code);
        tc.setDescription("Test tariff");
        tc.setCategory("GENERAL");
        tc.setUnitPrice(new BigDecimal("500.00"));
        tc.setCurrencyCode("USD");
        tc.setRequiresPreAuth(requiresPreAuth);
        tc.setCreatedAt(Instant.now());
        return tc;
    }
}
