package com.medfund.claims.service;

import com.medfund.claims.dto.AdjudicationResult;
import com.medfund.claims.dto.ClaimLineRequest;
import com.medfund.claims.dto.SubmitClaimRequest;
import com.medfund.claims.entity.Claim;
import com.medfund.claims.entity.ClaimLine;
import com.medfund.claims.exception.ClaimNotFoundException;
import com.medfund.claims.exception.InvalidClaimStateException;
import com.medfund.claims.repository.ClaimLineRepository;
import com.medfund.claims.repository.ClaimRepository;
import com.medfund.shared.audit.AuditPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ClaimServiceTest {

    @Mock
    private ClaimRepository claimRepository;

    @Mock
    private ClaimLineRepository claimLineRepository;

    @Mock
    private AuditPublisher auditPublisher;

    @Mock
    private ClaimEventPublisher eventPublisher;

    @Mock
    private AdjudicationPipeline adjudicationPipeline;

    @Mock
    private VerificationService verificationService;

    @InjectMocks
    private ClaimService claimService;

    private String actorId;
    private static final String ACTOR_EMAIL = "actor@test.example";

    @BeforeEach
    void setUp() {
        actorId = UUID.randomUUID().toString();
    }

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

        verify(claimRepository).findAllOrderByCreatedAtDesc();
    }

    @Test
    void findById_existing_returnsClaim() {
        Claim claim = createTestClaim();

        when(claimRepository.findById(claim.getId())).thenReturn(Mono.just(claim));

        StepVerifier.create(claimService.findById(claim.getId()))
                .expectNext(claim)
                .verifyComplete();

        verify(claimRepository).findById(claim.getId());
    }

    @Test
    void findById_nonExisting_throwsNotFound() {
        UUID id = UUID.randomUUID();

        when(claimRepository.findById(id)).thenReturn(Mono.empty());

        StepVerifier.create(claimService.findById(id))
                .expectError(ClaimNotFoundException.class)
                .verify();
    }

    @Test
    void submit_validRequest_createsClaimWithLines() {
        var lineRequest = new ClaimLineRequest(
                "TC001", "Consultation", 1,
                new BigDecimal("500.00"), new BigDecimal("500.00"),
                null, "USD"
        );
        var request = new SubmitClaimRequest(
                UUID.randomUUID(), null, UUID.randomUUID(), UUID.randomUUID(),
                null, null, LocalDate.now(), new BigDecimal("500.00"),
                null, null, null, null, List.of(lineRequest)
        );

        when(claimRepository.existsByClaimNumber(anyString())).thenReturn(Mono.just(false));
        when(claimRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(claimLineRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(verificationService.generateCode()).thenReturn("123456");
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        when(eventPublisher.publishClaimSubmitted(any(), any(), any())).thenReturn(Mono.empty());

        StepVerifier.create(
                claimService.submit(request, actorId, ACTOR_EMAIL)
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
                .assertNext(claim -> {
                    assertThat(claim.getClaimNumber()).startsWith("CLM-");
                    assertThat(claim.getStatus()).isEqualTo("SUBMITTED");
                    assertThat(claim.getVerificationCode()).isEqualTo("123456");
                    assertThat(claim.getMemberId()).isEqualTo(request.memberId());
                    assertThat(claim.getProviderId()).isEqualTo(request.providerId());
                    assertThat(claim.getSchemeId()).isEqualTo(request.schemeId());
                    assertThat(claim.getClaimedAmount()).isEqualByComparingTo(new BigDecimal("500.00"));
                })
                .verifyComplete();

        verify(claimRepository).existsByClaimNumber(anyString());
        verify(claimRepository).save(any(Claim.class));
        verify(claimLineRepository).save(any(ClaimLine.class));
        verify(auditPublisher).publish(any());
        verify(eventPublisher).publishClaimSubmitted(any(), any(), any());
    }

    @Test
    void verify_validCode_setsStatusVerified() {
        Claim claim = createTestClaim();
        claim.setStatus("SUBMITTED");
        claim.setVerificationCode("123456");

        when(claimRepository.findById(claim.getId())).thenReturn(Mono.just(claim));
        when(claimRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        when(eventPublisher.publishClaimStatusChanged(any(), any())).thenReturn(Mono.empty());

        StepVerifier.create(
                claimService.verify(claim.getId(), "123456", actorId, ACTOR_EMAIL)
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
                .assertNext(verified -> {
                    assertThat(verified.getStatus()).isEqualTo("VERIFIED");
                    assertThat(verified.getVerifiedAt()).isNotNull();
                })
                .verifyComplete();
    }

    @Test
    void verify_invalidCode_throwsException() {
        Claim claim = createTestClaim();
        claim.setStatus("SUBMITTED");
        claim.setVerificationCode("123456");

        when(claimRepository.findById(claim.getId())).thenReturn(Mono.just(claim));

        StepVerifier.create(
                claimService.verify(claim.getId(), "999999", actorId, ACTOR_EMAIL)
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
                .expectError(InvalidClaimStateException.class)
                .verify();
    }

    @Test
    void adjudicate_verifiedClaim_runsFullPipeline() {
        Claim claim = createTestClaim();
        claim.setStatus("VERIFIED");

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
                "APPROVED",
                new BigDecimal("500.00"),
                null,
                null,
                List.of(
                        new AdjudicationResult.StageResult("Eligibility", true, "Passed"),
                        new AdjudicationResult.StageResult("TariffPricing", true, "Passed")
                )
        );

        when(claimRepository.findById(claim.getId())).thenReturn(Mono.just(claim));
        when(claimRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(claimLineRepository.findByClaimId(claim.getId())).thenReturn(Flux.just(testClaimLine));
        when(adjudicationPipeline.execute(any(), any())).thenReturn(Mono.just(adjudicationResult));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        when(eventPublisher.publishClaimAdjudicated(any(), any(), any(), any(), any(), any())).thenReturn(Mono.empty());

        StepVerifier.create(
                claimService.adjudicate(claim.getId(), actorId, ACTOR_EMAIL)
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
                .assertNext(adjudicated -> {
                    assertThat(adjudicated.getStatus()).isEqualTo("ADJUDICATED");
                    assertThat(adjudicated.getApprovedAmount()).isEqualByComparingTo(new BigDecimal("500.00"));
                    assertThat(adjudicated.getAdjudicatedAt()).isNotNull();
                })
                .verifyComplete();

        verify(adjudicationPipeline).execute(any(Claim.class), anyList());
        verify(eventPublisher).publishClaimAdjudicated(any(), any(), any(), any(), any(), any());
    }

    // ---- Helper ----

    private Claim createTestClaim() {
        var claim = new Claim();
        claim.setId(UUID.randomUUID());
        claim.setClaimNumber("CLM-123456");
        claim.setMemberId(UUID.randomUUID());
        claim.setProviderId(UUID.randomUUID());
        claim.setSchemeId(UUID.randomUUID());
        claim.setClaimType("medical");
        claim.setStatus("SUBMITTED");
        claim.setServiceDate(LocalDate.now());
        claim.setClaimedAmount(new BigDecimal("500.00"));
        claim.setCurrencyCode("USD");
        claim.setVerificationCode("123456");
        claim.setCreatedAt(Instant.now());
        claim.setUpdatedAt(Instant.now());
        claim.setCreatedBy(UUID.randomUUID());
        claim.setUpdatedBy(UUID.randomUUID());
        return claim;
    }
}
