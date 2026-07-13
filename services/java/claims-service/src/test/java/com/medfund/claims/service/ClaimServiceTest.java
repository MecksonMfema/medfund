package com.medfund.claims.service;

import com.medfund.claims.client.SchemeClient;
import com.medfund.claims.dto.AdjudicationResult;
import com.medfund.claims.dto.ClaimAttachment;
import com.medfund.claims.dto.ClaimFilterParams;
import com.medfund.claims.dto.ClaimLineRequest;
import com.medfund.claims.dto.ClaimRow;
import com.medfund.claims.dto.SubmitClaimRequest;
import com.medfund.claims.entity.Claim;
import com.medfund.claims.entity.ClaimLine;
import com.medfund.claims.exception.ClaimNotFoundException;
import com.medfund.claims.repository.ClaimLineRepository;
import com.medfund.claims.repository.ClaimRepository;
import com.medfund.shared.audit.AuditPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class ClaimServiceTest {

    @Mock private ClaimRepository claimRepository;
    @Mock private ClaimLineRepository claimLineRepository;
    @Mock private com.medfund.claims.repository.ClaimQueryRepository claimQueryRepository;
    @Mock private AuditPublisher auditPublisher;
    @Mock private ClaimEventPublisher eventPublisher;
    @Mock private AdjudicationPipeline adjudicationPipeline;
    @Mock private SchemeClient schemeClient;
    @Mock private com.medfund.claims.repository.BeneficiaryBenefitRepository beneficiaryBenefitRepository;
    @Mock private org.springframework.r2dbc.core.DatabaseClient databaseClient;
    @Mock private TariffBenefitResolver tariffBenefitResolver;

    @InjectMocks
    private ClaimService claimService;

    private String actorId;
    private static final String ACTOR_EMAIL = "actor@test.example";

    @BeforeEach
    void setUp() {
        actorId = UUID.randomUUID().toString();
    }

    // ── Existing surface: findAll / findById ─────────────────────────

    @Test
    void findAll_returnsClaims() {
        Claim claim1 = createTestClaim();
        Claim claim2 = createTestClaim();
        claim2.setClaimNumber("CLM-654321");

        when(claimRepository.findAllOrderByCreatedAtDesc()).thenReturn(Flux.just(claim1, claim2));

        StepVerifier.create(claimService.findAll())
                .expectNext(claim1)
                .expectNext(claim2)
                .verifyComplete();
    }

    @Test
    void findById_existing_returnsClaim() {
        Claim claim = createTestClaim();
        when(claimRepository.findById(claim.getId())).thenReturn(Mono.just(claim));

        StepVerifier.create(claimService.findById(claim.getId()))
                .expectNext(claim)
                .verifyComplete();
    }

    @Test
    void findById_nonExisting_throwsNotFound() {
        UUID id = UUID.randomUUID();
        when(claimRepository.findById(id)).thenReturn(Mono.empty());

        StepVerifier.create(claimService.findById(id))
                .expectError(ClaimNotFoundException.class)
                .verify();
    }

    // ── submit() — operator flow lands VERIFIED, emits both events ───

    @Test
    void submit_health_landsVerifiedAndEmitsCapturedAlongsideSubmitted() {
        // Verification was removed on 2026-07-11: the operator vouches
        // for the capture, so the claim skips SUBMITTED → VERIFIED and
        // the "captured" event rides alongside "submitted" so the
        // ready-for-adjudication consumer picks it up immediately.
        var lineRequest = new ClaimLineRequest(
                "TC001", "Consultation", 1,
                new BigDecimal("500.00"), new BigDecimal("500.00"),
                null, "USD"
        );
        var request = healthRequest(List.of(lineRequest));

        stubHappyPathHealth();

        StepVerifier.create(
                claimService.submit(request, actorId, ACTOR_EMAIL)
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
                .assertNext(response -> {
                    assertThat(response.claim().claimNumber()).startsWith("CLM-");
                    assertThat(response.claim().status())
                            .withFailMessage("operator-captured claims must land VERIFIED — no separate verify hop")
                            .isEqualTo("VERIFIED");
                    assertThat(response.claim().insuranceLine()).isEqualTo("HEALTH");
                    // Batching is opt-in — this request didn't set one.
                    assertThat(response.batchNumber()).isNull();
                })
                .verifyComplete();

        verify(claimRepository).save(any(Claim.class));
        verify(claimLineRepository).save(any(ClaimLine.class));
        verify(eventPublisher).publishClaimSubmitted(any(), any(), any(), eq("HEALTH"));
        verify(eventPublisher).publishClaimCaptured(any(), any(), any(), eq("HEALTH"));
    }

    // ── submit() — insurance line derived from scheme, hint is advisory ──

    @Test
    void submit_derivesInsuranceLineFromScheme_ignoresConflictingHint() {
        var lineRequest = new ClaimLineRequest("TC001", "Consultation", 1,
                new BigDecimal("500.00"), new BigDecimal("500.00"), null, "USD");
        var request = withInsuranceLineHint(healthRequest(List.of(lineRequest)), "VEHICLE");

        stubHappyPathHealth();

        StepVerifier.create(
                claimService.submit(request, actorId, ACTOR_EMAIL)
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
                .assertNext(response -> {
                    assertThat(response.claim().insuranceLine())
                            .withFailMessage("must persist the scheme-derived line, not the client hint")
                            .isEqualTo("HEALTH");
                })
                .verifyComplete();
    }

    // ── submit() — per-line required-field enforcement ───────────────

    @Test
    void submit_health_rejectsEmptyLines() {
        var request = healthRequest(List.of());
        when(schemeClient.findById(request.schemeId()))
                .thenReturn(Mono.just(schemeSummary("HEALTH")));

        StepVerifier.create(
                claimService.submit(request, actorId, ACTOR_EMAIL)
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(IllegalArgumentException.class);
                    assertThat(err.getMessage()).contains("HEALTH", "tariff line");
                })
                .verify();

        verify(claimRepository, never()).save(any(Claim.class));
    }

    @Test
    void submit_vehicle_rejectsMissingRegistration() {
        var request = vehicleRequest(null, "Harare CBD");
        when(schemeClient.findById(request.schemeId()))
                .thenReturn(Mono.just(schemeSummary("VEHICLE")));

        StepVerifier.create(
                claimService.submit(request, actorId, ACTOR_EMAIL)
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(IllegalArgumentException.class);
                    assertThat(err.getMessage()).contains("VEHICLE", "vehicleRegistration");
                })
                .verify();
    }

    @Test
    void submit_vehicle_rejectsTariffLines() {
        var lineRequest = new ClaimLineRequest("TC001", "Bodywork", 1,
                new BigDecimal("300.00"), new BigDecimal("300.00"), null, "USD");
        var request = withLines(vehicleRequest("ABC 1234", "Harare CBD"), List.of(lineRequest));
        when(schemeClient.findById(request.schemeId()))
                .thenReturn(Mono.just(schemeSummary("VEHICLE")));

        StepVerifier.create(
                claimService.submit(request, actorId, ACTOR_EMAIL)
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(IllegalArgumentException.class);
                    assertThat(err.getMessage()).contains("VEHICLE", "do not accept tariff lines");
                })
                .verify();
    }

    // ── submit() — batching + attachments ───────────────────────────

    @Test
    void submit_supplied_batchNumber_isPreserved() {
        var lineRequest = new ClaimLineRequest("TC001", "Consultation", 1,
                new BigDecimal("500.00"), new BigDecimal("500.00"), null, "USD");
        var request = withBatchNumber(healthRequest(List.of(lineRequest)), "BATCH777");

        stubHappyPathHealth();

        ArgumentCaptor<Claim> claimCaptor = ArgumentCaptor.forClass(Claim.class);

        StepVerifier.create(
                claimService.submit(request, actorId, ACTOR_EMAIL)
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
                .assertNext(response -> {
                    assertThat(response.batchNumber()).isEqualTo("BATCH777");
                    assertThat(response.claim().batchNumber()).isEqualTo("BATCH777");
                })
                .verifyComplete();

        verify(claimRepository).save(claimCaptor.capture());
        assertThat(claimCaptor.getValue().getBatchNumber()).isEqualTo("BATCH777");
    }

    @Test
    void submit_blankBatchNumber_isNormalisedToNull() {
        var lineRequest = new ClaimLineRequest("TC001", "Consultation", 1,
                new BigDecimal("500.00"), new BigDecimal("500.00"), null, "USD");
        var request = withBatchNumber(healthRequest(List.of(lineRequest)), "   ");

        stubHappyPathHealth();

        StepVerifier.create(
                claimService.submit(request, actorId, ACTOR_EMAIL)
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
                .assertNext(response -> {
                    assertThat(response.batchNumber()).isNull();
                    assertThat(response.claim().batchNumber()).isNull();
                })
                .verifyComplete();
    }

    @Test
    void submit_attachments_areSerialisedToJsonOnTheClaim() {
        var lineRequest = new ClaimLineRequest("TC001", "Consultation", 1,
                new BigDecimal("500.00"), new BigDecimal("500.00"), null, "USD");
        var attachments = List.of(
                new ClaimAttachment("receipt.pdf", "application/pdf", 12345L),
                new ClaimAttachment("photo.jpg",   "image/jpeg",       98765L)
        );
        var request = withAttachments(healthRequest(List.of(lineRequest)), attachments);

        stubHappyPathHealth();

        ArgumentCaptor<Claim> claimCaptor = ArgumentCaptor.forClass(Claim.class);

        StepVerifier.create(
                claimService.submit(request, actorId, ACTOR_EMAIL)
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
                .assertNext(response -> {
                    assertThat(response.claim().attachments()).hasSize(2);
                    assertThat(response.claim().attachments().get(0).filename()).isEqualTo("receipt.pdf");
                    assertThat(response.claim().attachments().get(1).contentType()).isEqualTo("image/jpeg");
                })
                .verifyComplete();

        verify(claimRepository).save(claimCaptor.capture());
        assertThat(claimCaptor.getValue().getAttachmentsJson())
                .withFailMessage("attachments must be persisted as JSON on the claim entity")
                .contains("receipt.pdf", "photo.jpg", "image/jpeg");
    }

    @Test
    void submit_noAttachments_storesNull() {
        var lineRequest = new ClaimLineRequest("TC001", "Consultation", 1,
                new BigDecimal("500.00"), new BigDecimal("500.00"), null, "USD");
        var request = healthRequest(List.of(lineRequest));

        stubHappyPathHealth();

        ArgumentCaptor<Claim> claimCaptor = ArgumentCaptor.forClass(Claim.class);

        StepVerifier.create(
                claimService.submit(request, actorId, ACTOR_EMAIL)
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
                .assertNext(response -> {
                    assertThat(response.claim().attachments()).isEmpty();
                })
                .verifyComplete();

        verify(claimRepository).save(claimCaptor.capture());
        assertThat(claimCaptor.getValue().getAttachmentsJson()).isNull();
    }

    // ── submit() — provider policy per line ─────────────────────────

    @Test
    void submit_life_rejectsProviderPresent() {
        // FORBIDDEN lines (LIFE / DISABILITY) are paid straight to the
        // member. Attaching a provider would let the finance consumer
        // eventually pay the wrong party — reject at capture time.
        var request = withInsuranceLineHint(healthRequest(List.of()), "LIFE");
        request = new SubmitClaimRequest(
                request.memberId(), request.dependantId(), request.providerId(), request.schemeId(),
                request.benefitId(), request.claimType(), request.insuranceLine(), request.batchNumber(),
                request.serviceDate(), request.claimedAmount(),
                request.currencyCode(), request.diagnosisCodes(), request.procedureCodes(), request.notes(),
                request.lines(),
                request.vehicleRegistration(), request.incidentLocation(), request.incidentReportRef(),
                request.policeReportRef(), request.propertyAddress(), request.deathCertificateRef(),
                request.deceasedRelationship(), request.travelDestination(), request.travelStartDate(),
                request.travelEndDate(), request.disabilityAssessmentRef(), "LIFE-CERT-2026-77",
                request.attachments()
        );
        when(schemeClient.findById(request.schemeId()))
                .thenReturn(Mono.just(schemeSummary("LIFE")));

        SubmitClaimRequest finalRequest = request;
        StepVerifier.create(
                claimService.submit(finalRequest, actorId, ACTOR_EMAIL)
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(IllegalArgumentException.class);
                    assertThat(err.getMessage()).contains("LIFE", "paid to the member");
                })
                .verify();
    }

    @Test
    void submit_life_acceptsNoProvider_andPersists() {
        var lifeRequest = new SubmitClaimRequest(
                UUID.randomUUID(), null, null /* no provider */, UUID.randomUUID(),
                null, null, null, null,
                LocalDate.now(), new BigDecimal("50000.00"),
                "USD", null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null,
                "LIFE-CERT-2026-77",
                null
        );
        when(schemeClient.findById(any(UUID.class)))
                .thenReturn(Mono.just(schemeSummary("LIFE")));
        when(claimRepository.existsByClaimNumber(anyString())).thenReturn(Mono.just(false));
        when(claimRepository.save(any())).thenAnswer(inv -> {
            Claim saved = inv.getArgument(0);
            if (saved.getId() == null) saved.setId(UUID.randomUUID());
            return Mono.just(saved);
        });
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        when(eventPublisher.publishClaimSubmitted(any(), any(), any(), any())).thenReturn(Mono.empty());
        when(eventPublisher.publishClaimCaptured(any(), any(), any(), any())).thenReturn(Mono.empty());

        StepVerifier.create(
                claimService.submit(lifeRequest, actorId, ACTOR_EMAIL)
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
                .assertNext(response -> {
                    assertThat(response.claim().insuranceLine()).isEqualTo("LIFE");
                    assertThat(response.claim().providerId()).isNull();
                })
                .verifyComplete();
    }

    @Test
    void submit_health_acceptsMissingProvider_asMemberReimbursement() {
        // Member-reimbursement HEALTH claim: no provider, no bill from a
        // network — just a receipt the operator captured. Regressing this
        // puts every out-of-pocket capture behind a "pick a provider"
        // wall the operator can't satisfy.
        var lineRequest = new ClaimLineRequest("TC001", "Consultation", 1,
                new BigDecimal("500.00"), new BigDecimal("500.00"), null, "USD");
        var request = new SubmitClaimRequest(
                UUID.randomUUID(), null, null /* member paid */, UUID.randomUUID(),
                null, null, null, null,
                LocalDate.now(), new BigDecimal("500.00"),
                "USD", null, null, null, List.of(lineRequest),
                null, null, null, null, null, null, null, null, null, null, null, null,
                null
        );
        stubHappyPathHealth();

        StepVerifier.create(
                claimService.submit(request, actorId, ACTOR_EMAIL)
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
                .assertNext(response -> {
                    assertThat(response.claim().insuranceLine()).isEqualTo("HEALTH");
                    assertThat(response.claim().providerId()).isNull();
                })
                .verifyComplete();
    }

    // ── adjudicate() — insurance line rides on the outgoing event ───

    @Test
    void adjudicate_verifiedClaim_runsFullPipelineAndPropagatesLine() {
        Claim claim = createTestClaim();
        claim.setStatus("VERIFIED");
        claim.setInsuranceLine("HEALTH");

        ClaimLine testClaimLine = new ClaimLine();
        testClaimLine.setId(UUID.randomUUID());
        testClaimLine.setClaimId(claim.getId());
        testClaimLine.setTariffCode("TC001");
        testClaimLine.setDescription("Consultation");
        testClaimLine.setQuantity(1);
        testClaimLine.setUnitPrice(new BigDecimal("500.00"));
        testClaimLine.setClaimedAmount(new BigDecimal("500.00"));
        testClaimLine.setCurrencyCode("USD");
        testClaimLine.setCreatedAt(Instant.now());

        var adjudicationResult = new AdjudicationResult(
                "APPROVED", new BigDecimal("500.00"), null, null,
                List.of(new AdjudicationResult.StageResult("Eligibility", true, "Passed"))
        );

        when(claimRepository.findById(claim.getId())).thenReturn(Mono.just(claim));
        when(claimRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(claimLineRepository.findByClaimId(claim.getId())).thenReturn(Flux.just(testClaimLine));
        when(adjudicationPipeline.execute(any(), any())).thenReturn(Mono.just(adjudicationResult));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        when(eventPublisher.publishClaimAdjudicated(any(), any(), any(), any(), any(), any(), any(),
                        any(), any(), any(), any()))
                .thenReturn(Mono.empty());

        StepVerifier.create(
                claimService.adjudicate(claim.getId(), actorId, ACTOR_EMAIL)
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
                .assertNext(adjudicated -> {
                    assertThat(adjudicated.getStatus()).isEqualTo("ADJUDICATED");
                    assertThat(adjudicated.getApprovedAmount()).isEqualByComparingTo(new BigDecimal("500.00"));
                })
                .verifyComplete();

        verify(adjudicationPipeline).execute(any(Claim.class), anyList());
        verify(eventPublisher).publishClaimAdjudicated(any(), any(), any(), any(), any(), any(), eq("HEALTH"),
                any(), any(), any(), any());
    }

    // ── V063 ingestion — line.benefit_id resolved from tariff ────────

    /**
     * V063 — every health-claim line is routed through
     * {@link TariffBenefitResolver#resolve(String, UUID)} at ingestion
     * so the decrement consumer knows which benefit ledger to touch.
     * Guards the wiring itself (arg values) — the resolver's SQL
     * branches are covered separately by {@link TariffBenefitResolverTest}.
     */
    @Test
    void submit_health_invokesTariffResolverPerLineWithSchemeContext() {
        var line1 = new ClaimLineRequest("TC001", "Consultation", 1,
                new BigDecimal("500.00"), new BigDecimal("500.00"), null, "USD");
        var line2 = new ClaimLineRequest("TC099", "Radiology", 1,
                new BigDecimal("250.00"), new BigDecimal("250.00"), null, "USD");
        var request = healthRequest(List.of(line1, line2));

        stubHappyPathHealth();

        StepVerifier.create(
                claimService.submit(request, actorId, ACTOR_EMAIL)
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        ).expectNextCount(1).verifyComplete();

        // Both lines get resolved with the same scheme id — Mockito can
        // count matching invocations across argument variants.
        verify(tariffBenefitResolver).resolve(eq("TC001"), eq(request.schemeId()));
        verify(tariffBenefitResolver).resolve(eq("TC099"), eq(request.schemeId()));
    }

    /**
     * V063 — when the resolver returns a scheme_benefit UUID, the saved
     * line carries it on {@code benefit_id}. When the resolver returns
     * empty (cap-only or unmapped), the line's benefit_id stays null.
     * This is the ingestion-time contract the decrement consumer relies
     * on to decide whether to touch a per-benefit ledger row.
     */
    @Test
    void submit_health_setsBenefitIdOnLineWhenResolverEmits() {
        var lineReq = new ClaimLineRequest("TC001", "Consultation", 1,
                new BigDecimal("500.00"), new BigDecimal("500.00"), null, "USD");
        var request = healthRequest(List.of(lineReq));
        stubHappyPathHealth();

        UUID resolvedBenefitId = UUID.randomUUID();
        when(tariffBenefitResolver.resolve(eq("TC001"), any(UUID.class)))
                .thenReturn(Mono.just(resolvedBenefitId));

        StepVerifier.create(
                claimService.submit(request, actorId, ACTOR_EMAIL)
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        ).expectNextCount(1).verifyComplete();

        ArgumentCaptor<ClaimLine> lineCaptor = ArgumentCaptor.forClass(ClaimLine.class);
        verify(claimLineRepository).save(lineCaptor.capture());
        assertThat(lineCaptor.getValue().getBenefitId()).isEqualTo(resolvedBenefitId);
        assertThat(lineCaptor.getValue().getTariffCode()).isEqualTo("TC001");
    }

    // ── Fixtures ─────────────────────────────────────────────────────

    private void stubHappyPathHealth() {
        when(schemeClient.findById(any(UUID.class)))
                .thenReturn(Mono.just(schemeSummary("HEALTH")));
        when(claimRepository.existsByClaimNumber(anyString())).thenReturn(Mono.just(false));
        // The service reads .getId() off the saved claim to build audit +
        // event payloads. R2DBC gives the entity a generated id on insert
        // — the mock has to do the same, otherwise every downstream leg
        // NPEs. See memory: bug_claim_save_mock_id_npe.
        when(claimRepository.save(any())).thenAnswer(inv -> {
            Claim saved = inv.getArgument(0);
            if (saved.getId() == null) saved.setId(UUID.randomUUID());
            return Mono.just(saved);
        });
        when(claimLineRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        when(eventPublisher.publishClaimSubmitted(any(), any(), any(), any())).thenReturn(Mono.empty());
        when(eventPublisher.publishClaimCaptured(any(), any(), any(), any())).thenReturn(Mono.empty());
        // V063 — every claim line goes through the resolver at ingestion.
        // Default to Mono.empty() (unmapped / cap-only) so line.benefit_id
        // stays null unless a specific test overrides.
        when(tariffBenefitResolver.resolve(anyString(), any(UUID.class))).thenReturn(Mono.empty());
    }

    private SchemeClient.SchemeSummary schemeSummary(String line) {
        String type = switch (line) {
            case "VEHICLE"  -> "comprehensive";
            case "FUNERAL"  -> "funeral_benefit";
            case "LIFE"     -> "term_life";
            case "PROPERTY" -> "buildings";
            default         -> "medical_aid";
        };
        return new SchemeClient.SchemeSummary(UUID.randomUUID(), line + " Test Scheme", type, line, "USD");
    }

    private SubmitClaimRequest healthRequest(List<ClaimLineRequest> lines) {
        return new SubmitClaimRequest(
                UUID.randomUUID(), null, UUID.randomUUID(), UUID.randomUUID(),
                null, null, null, null,
                LocalDate.now(), new BigDecimal("500.00"),
                "USD", null, null, null, lines,
                null, null, null, null, null, null, null, null, null, null, null, null,
                null
        );
    }

    private SubmitClaimRequest vehicleRequest(String vehicleReg, String incidentLocation) {
        return new SubmitClaimRequest(
                UUID.randomUUID(), null, UUID.randomUUID(), UUID.randomUUID(),
                null, null, null, null,
                LocalDate.now(), new BigDecimal("1200.00"),
                "USD", null, null, null, null,
                vehicleReg, incidentLocation, null, null, null, null, null, null, null, null, null, null,
                null
        );
    }

    private SubmitClaimRequest withInsuranceLineHint(SubmitClaimRequest req, String hint) {
        return new SubmitClaimRequest(
                req.memberId(), req.dependantId(), req.providerId(), req.schemeId(),
                req.benefitId(), req.claimType(), hint, req.batchNumber(),
                req.serviceDate(), req.claimedAmount(),
                req.currencyCode(), req.diagnosisCodes(), req.procedureCodes(), req.notes(),
                req.lines(),
                req.vehicleRegistration(), req.incidentLocation(), req.incidentReportRef(),
                req.policeReportRef(), req.propertyAddress(), req.deathCertificateRef(),
                req.deceasedRelationship(), req.travelDestination(), req.travelStartDate(),
                req.travelEndDate(), req.disabilityAssessmentRef(), req.lifeCertificateRef(),
                req.attachments()
        );
    }

    private SubmitClaimRequest withBatchNumber(SubmitClaimRequest req, String batch) {
        return new SubmitClaimRequest(
                req.memberId(), req.dependantId(), req.providerId(), req.schemeId(),
                req.benefitId(), req.claimType(), req.insuranceLine(), batch,
                req.serviceDate(), req.claimedAmount(),
                req.currencyCode(), req.diagnosisCodes(), req.procedureCodes(), req.notes(),
                req.lines(),
                req.vehicleRegistration(), req.incidentLocation(), req.incidentReportRef(),
                req.policeReportRef(), req.propertyAddress(), req.deathCertificateRef(),
                req.deceasedRelationship(), req.travelDestination(), req.travelStartDate(),
                req.travelEndDate(), req.disabilityAssessmentRef(), req.lifeCertificateRef(),
                req.attachments()
        );
    }

    private SubmitClaimRequest withLines(SubmitClaimRequest req, List<ClaimLineRequest> lines) {
        return new SubmitClaimRequest(
                req.memberId(), req.dependantId(), req.providerId(), req.schemeId(),
                req.benefitId(), req.claimType(), req.insuranceLine(), req.batchNumber(),
                req.serviceDate(), req.claimedAmount(),
                req.currencyCode(), req.diagnosisCodes(), req.procedureCodes(), req.notes(),
                lines,
                req.vehicleRegistration(), req.incidentLocation(), req.incidentReportRef(),
                req.policeReportRef(), req.propertyAddress(), req.deathCertificateRef(),
                req.deceasedRelationship(), req.travelDestination(), req.travelStartDate(),
                req.travelEndDate(), req.disabilityAssessmentRef(), req.lifeCertificateRef(),
                req.attachments()
        );
    }

    private SubmitClaimRequest withAttachments(SubmitClaimRequest req, List<ClaimAttachment> attachments) {
        return new SubmitClaimRequest(
                req.memberId(), req.dependantId(), req.providerId(), req.schemeId(),
                req.benefitId(), req.claimType(), req.insuranceLine(), req.batchNumber(),
                req.serviceDate(), req.claimedAmount(),
                req.currencyCode(), req.diagnosisCodes(), req.procedureCodes(), req.notes(),
                req.lines(),
                req.vehicleRegistration(), req.incidentLocation(), req.incidentReportRef(),
                req.policeReportRef(), req.propertyAddress(), req.deathCertificateRef(),
                req.deceasedRelationship(), req.travelDestination(), req.travelStartDate(),
                req.travelEndDate(), req.disabilityAssessmentRef(), req.lifeCertificateRef(),
                attachments
        );
    }

    private Claim createTestClaim() {
        var claim = new Claim();
        claim.setId(UUID.randomUUID());
        claim.setClaimNumber("CLM-123456");
        claim.setMemberId(UUID.randomUUID());
        claim.setProviderId(UUID.randomUUID());
        claim.setSchemeId(UUID.randomUUID());
        claim.setClaimType("medical");
        claim.setInsuranceLine("HEALTH");
        claim.setStatus("VERIFIED");
        claim.setServiceDate(LocalDate.now());
        claim.setClaimedAmount(new BigDecimal("500.00"));
        claim.setCurrencyCode("USD");
        claim.setCreatedAt(Instant.now());
        claim.setUpdatedAt(Instant.now());
        claim.setCreatedBy(UUID.randomUUID());
        claim.setUpdatedBy(UUID.randomUUID());
        return claim;
    }

    // ── searchPaged — envelope + clamp contract ─────────────────────

    @Test
    void searchPaged_wrapsQueryRepoRowsInPageResponse() {
        var row = new ClaimRow(
                UUID.randomUUID(), "CLM-000001",
                UUID.randomUUID(), "Alice Ndlovu", "MBR-000001", null,
                UUID.randomUUID(), "Harare Clinic",
                UUID.randomUUID(), "medical", "HEALTH", "VERIFIED",
                LocalDate.now(), Instant.now(),
                new BigDecimal("500.00"), null, "USD", null, Instant.now());

        var params = new ClaimFilterParams(
                "VERIFIED", null, null, null, null, null, null,
                "submissionDate", "desc", 0, 50);

        org.mockito.Mockito.when(
                claimQueryRepository.search(org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.eq(50), org.mockito.ArgumentMatchers.eq(0)))
                .thenReturn(Flux.just(row));
        org.mockito.Mockito.when(
                claimQueryRepository.count(org.mockito.ArgumentMatchers.any()))
                .thenReturn(Mono.just(1L));

        StepVerifier.create(claimService.searchPaged(params))
                .assertNext(pageResp -> {
                    org.assertj.core.api.Assertions.assertThat(pageResp.content()).containsExactly(row);
                    org.assertj.core.api.Assertions.assertThat(pageResp.total()).isEqualTo(1L);
                    org.assertj.core.api.Assertions.assertThat(pageResp.page()).isZero();
                    org.assertj.core.api.Assertions.assertThat(pageResp.size()).isEqualTo(50);
                    org.assertj.core.api.Assertions.assertThat(pageResp.totalPages()).isEqualTo(1);
                })
                .verifyComplete();
    }

    @Test
    void searchPaged_clampsSizeAndPage() {
        // Negative page → 0. Size 99999 → 200. Keeps the "one page won't
        // blow up the client" contract regardless of what a caller asks for.
        var params = new ClaimFilterParams(
                null, null, null, null, null, null, null,
                "submissionDate", "desc", -3, 99999);

        org.mockito.Mockito.when(
                claimQueryRepository.search(org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.eq(200), org.mockito.ArgumentMatchers.eq(0)))
                .thenReturn(Flux.empty());
        org.mockito.Mockito.when(
                claimQueryRepository.count(org.mockito.ArgumentMatchers.any()))
                .thenReturn(Mono.just(0L));

        StepVerifier.create(claimService.searchPaged(params))
                .assertNext(pageResp -> {
                    org.assertj.core.api.Assertions.assertThat(pageResp.page()).isZero();
                    org.assertj.core.api.Assertions.assertThat(pageResp.size()).isEqualTo(200);
                })
                .verifyComplete();
    }
}
