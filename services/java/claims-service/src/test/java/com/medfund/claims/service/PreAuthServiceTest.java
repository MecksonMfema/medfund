package com.medfund.claims.service;

import com.medfund.claims.dto.PreAuthRequest;
import com.medfund.claims.entity.PreAuthorization;
import com.medfund.claims.exception.PreAuthNotFoundException;
import com.medfund.claims.repository.PreAuthorizationRepository;
import com.medfund.shared.audit.AuditPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PreAuthServiceTest {

    @Mock
    private PreAuthorizationRepository preAuthorizationRepository;

    @Mock
    private AuditPublisher auditPublisher;

    @Mock
    private ClaimEventPublisher eventPublisher;

    @InjectMocks
    private PreAuthService preAuthService;

    private String actorId;
    private static final String ACTOR_EMAIL = "actor@test.example";

    @BeforeEach
    void setUp() {
        actorId = UUID.randomUUID().toString();
    }

    @Test
    void findById_existing_returnsPreAuth() {
        PreAuthorization preAuth = createTestPreAuth();

        when(preAuthorizationRepository.findById(preAuth.getId())).thenReturn(Mono.just(preAuth));

        StepVerifier.create(preAuthService.findById(preAuth.getId()))
                .expectNext(preAuth)
                .verifyComplete();

        verify(preAuthorizationRepository).findById(preAuth.getId());
    }

    @Test
    void findById_nonExisting_throwsNotFound() {
        UUID id = UUID.randomUUID();

        when(preAuthorizationRepository.findById(id)).thenReturn(Mono.empty());

        StepVerifier.create(preAuthService.findById(id))
                .expectError(PreAuthNotFoundException.class)
                .verify();
    }

    @Test
    void request_validRequest_createsPreAuth() {
        var request = new PreAuthRequest(
                UUID.randomUUID(), null, UUID.randomUUID(), UUID.randomUUID(),
                "TC001", "J06.9", new BigDecimal("1000.00"), "USD", "Urgent procedure"
        );

        when(preAuthorizationRepository.existsByAuthNumber(anyString())).thenReturn(Mono.just(false));
        when(preAuthorizationRepository.save(any())).thenAnswer(inv -> {
            var saved = (com.medfund.claims.entity.PreAuthorization) inv.getArgument(0);
            if (saved.getId() == null) saved.setId(UUID.randomUUID());
            return Mono.just(saved);
        });
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
                preAuthService.request(request, actorId, ACTOR_EMAIL)
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
                .assertNext(preAuth -> {
                    assertThat(preAuth.getAuthNumber()).startsWith("PA-");
                    assertThat(preAuth.getStatus()).isEqualTo("PENDING");
                    assertThat(preAuth.getMemberId()).isEqualTo(request.memberId());
                    assertThat(preAuth.getDependantId()).isNull();
                    assertThat(preAuth.getProviderId()).isEqualTo(request.providerId());
                    assertThat(preAuth.getSchemeId()).isEqualTo(request.schemeId());
                    assertThat(preAuth.getTariffCode()).isEqualTo("TC001");
                    assertThat(preAuth.getRequestedAmount()).isEqualByComparingTo(new BigDecimal("1000.00"));
                    assertThat(preAuth.getId()).isNotNull();
                })
                .verifyComplete();

        verify(preAuthorizationRepository).save(any(PreAuthorization.class));
        verify(auditPublisher).publish(any());
    }

    @Test
    void request_forDependant_persistsDependantId() {
        UUID sponsorMemberId = UUID.randomUUID();
        UUID dependantId = UUID.randomUUID();
        var request = new PreAuthRequest(
                sponsorMemberId, dependantId, UUID.randomUUID(), UUID.randomUUID(),
                "TC002", "J06.9", new BigDecimal("500.00"), "USD", "Dependant elective"
        );

        when(preAuthorizationRepository.existsByAuthNumber(anyString())).thenReturn(Mono.just(false));
        when(preAuthorizationRepository.save(any())).thenAnswer(inv -> {
            var saved = (com.medfund.claims.entity.PreAuthorization) inv.getArgument(0);
            if (saved.getId() == null) saved.setId(UUID.randomUUID());
            return Mono.just(saved);
        });
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
                preAuthService.request(request, actorId, ACTOR_EMAIL)
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
                .assertNext(preAuth -> {
                    assertThat(preAuth.getMemberId()).isEqualTo(sponsorMemberId);
                    assertThat(preAuth.getDependantId()).isEqualTo(dependantId);
                    assertThat(preAuth.getTariffCode()).isEqualTo("TC002");
                })
                .verifyComplete();
    }

    @Test
    void approve_existingPreAuth_setsStatusApproved() {
        PreAuthorization preAuth = createTestPreAuth();
        preAuth.setStatus("PENDING");

        BigDecimal approvedAmount = new BigDecimal("800.00");
        // feedback_effective_date_snap: PreAuthService snaps expiryDate to
        // end-of-month so the auth stays valid through the whole cycle it
        // expires in.
        LocalDate expiryDate = LocalDate.now().plusMonths(3);
        LocalDate expectedExpiry = expiryDate.withDayOfMonth(expiryDate.lengthOfMonth());

        when(preAuthorizationRepository.findById(preAuth.getId())).thenReturn(Mono.just(preAuth));
        when(preAuthorizationRepository.save(any())).thenAnswer(inv -> {
            var saved = (com.medfund.claims.entity.PreAuthorization) inv.getArgument(0);
            if (saved.getId() == null) saved.setId(UUID.randomUUID());
            return Mono.just(saved);
        });
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        when(eventPublisher.publishPreAuthDecision(any(), any(), any())).thenReturn(Mono.empty());

        StepVerifier.create(
                preAuthService.approve(preAuth.getId(), approvedAmount, expiryDate, actorId, ACTOR_EMAIL)
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
                .assertNext(approved -> {
                    assertThat(approved.getStatus()).isEqualTo("APPROVED");
                    assertThat(approved.getApprovedAmount()).isEqualByComparingTo(approvedAmount);
                    assertThat(approved.getDecisionDate()).isEqualTo(LocalDate.now());
                    assertThat(approved.getExpiryDate()).isEqualTo(expectedExpiry);
                })
                .verifyComplete();

        verify(eventPublisher).publishPreAuthDecision(any(), any(), eq("APPROVED"));
    }

    @Test
    void reject_existingPreAuth_setsStatusRejected() {
        PreAuthorization preAuth = createTestPreAuth();
        preAuth.setStatus("PENDING");

        String rejectionReason = "Insufficient medical justification";

        when(preAuthorizationRepository.findById(preAuth.getId())).thenReturn(Mono.just(preAuth));
        when(preAuthorizationRepository.save(any())).thenAnswer(inv -> {
            var saved = (com.medfund.claims.entity.PreAuthorization) inv.getArgument(0);
            if (saved.getId() == null) saved.setId(UUID.randomUUID());
            return Mono.just(saved);
        });
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        when(eventPublisher.publishPreAuthDecision(any(), any(), any())).thenReturn(Mono.empty());

        StepVerifier.create(
                preAuthService.reject(preAuth.getId(), rejectionReason, actorId, ACTOR_EMAIL)
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
                .assertNext(rejected -> {
                    assertThat(rejected.getStatus()).isEqualTo("REJECTED");
                    assertThat(rejected.getRejectionReason()).isEqualTo(rejectionReason);
                    assertThat(rejected.getDecisionDate()).isEqualTo(LocalDate.now());
                })
                .verifyComplete();

        verify(eventPublisher).publishPreAuthDecision(any(), any(), eq("REJECTED"));
    }

    @Test
    void hasValidPreAuth_approvedAndNotExpired_returnsTrue() {
        UUID memberId = UUID.randomUUID();
        String tariffCode = "TC001";

        PreAuthorization preAuth = createTestPreAuth();
        preAuth.setStatus("APPROVED");
        preAuth.setExpiryDate(LocalDate.now().plusMonths(1));

        when(preAuthorizationRepository.findByMemberIdAndTariffCodeAndStatus(memberId, tariffCode, "APPROVED"))
                .thenReturn(Mono.just(preAuth));

        StepVerifier.create(preAuthService.hasValidPreAuth(memberId, tariffCode))
                .assertNext(result -> assertThat(result).isTrue())
                .verifyComplete();
    }

    @Test
    void hasValidPreAuth_noMatch_returnsFalse() {
        UUID memberId = UUID.randomUUID();
        String tariffCode = "TC999";

        when(preAuthorizationRepository.findByMemberIdAndTariffCodeAndStatus(memberId, tariffCode, "APPROVED"))
                .thenReturn(Mono.empty());

        StepVerifier.create(preAuthService.hasValidPreAuth(memberId, tariffCode))
                .assertNext(result -> assertThat(result).isFalse())
                .verifyComplete();
    }

    // ---- Helper ----

    private PreAuthorization createTestPreAuth() {
        var preAuth = new PreAuthorization();
        preAuth.setId(UUID.randomUUID());
        preAuth.setAuthNumber("PA-123456");
        preAuth.setMemberId(UUID.randomUUID());
        preAuth.setProviderId(UUID.randomUUID());
        preAuth.setSchemeId(UUID.randomUUID());
        preAuth.setTariffCode("TC001");
        preAuth.setDiagnosisCode("J06.9");
        preAuth.setStatus("PENDING");
        preAuth.setRequestedAmount(new BigDecimal("1000.00"));
        preAuth.setCurrencyCode("USD");
        preAuth.setRequestedDate(LocalDate.now());
        preAuth.setCreatedAt(Instant.now());
        preAuth.setUpdatedAt(Instant.now());
        preAuth.setCreatedBy(UUID.randomUUID());
        return preAuth;
    }
}
