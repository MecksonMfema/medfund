package com.medfund.claims.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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

    private AdjudicationPipeline adjudicationPipeline;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        adjudicationPipeline = new AdjudicationPipeline(
                tariffCodeRepository, tariffModifierRepository,
                icdCodeRepository, diagnosisProcedureMappingRepository,
                preAuthorizationRepository, rejectionReasonRepository,
                ruleEvaluationService, tenantRuleLoader, factBuilder,
                databaseClient, new ObjectMapper()
        );

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
