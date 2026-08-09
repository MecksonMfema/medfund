package com.medfund.finance.service;

import com.medfund.finance.client.FxConverter;
import com.medfund.finance.client.TenantConfigClient;
import com.medfund.finance.client.TenantConfigClient.AdvancePaymentThreshold;
import com.medfund.finance.dto.AdvancePaymentDtos.CreateAdvancePaymentRequest;
import com.medfund.finance.dto.AdvancePaymentDtos.ReverseAdvancePaymentRequest;
import com.medfund.finance.entity.AdvancePayment;
import com.medfund.finance.entity.ProviderBalance;
import com.medfund.finance.repository.AdvancePaymentApplicationRepository;
import com.medfund.finance.repository.AdvancePaymentQueryRepository;
import com.medfund.finance.repository.AdvancePaymentRepository;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdvancePaymentServiceTest {

    @Mock private AdvancePaymentRepository repository;
    @Mock private AdvancePaymentApplicationRepository applicationRepository;
    @Mock private AdvancePaymentQueryRepository queryRepository;
    @Mock private AuditPublisher auditPublisher;
    @Mock private FinanceEventPublisher eventPublisher;
    @Mock private TenantConfigClient tenantConfigClient;
    @Mock private FxConverter fxConverter;
    @Mock private ProviderBalanceService providerBalanceService;

    @InjectMocks
    private AdvancePaymentService service;

    @BeforeEach
    void wireCommonMocks() {
        // save mock has to assign an id so the audit path can call getId().toString() —
        // the classic bug_claim_save_mock_id_npe pattern (see auto-memory).
        lenient().when(repository.save(any())).thenAnswer(inv -> {
            AdvancePayment saved = inv.getArgument(0);
            if (saved.getId() == null) saved.setId(UUID.randomUUID());
            if (saved.getRecordedAt() == null) saved.setRecordedAt(Instant.now());
            return Mono.just(saved);
        });
        lenient().when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        lenient().when(eventPublisher.publishAdvanceApproved(any())).thenReturn(Mono.empty());
        lenient().when(eventPublisher.publishAdvanceReversed(any(), any())).thenReturn(Mono.empty());
        lenient().when(providerBalanceService.updateBalance(any(), anyString(), any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(new ProviderBalance()));
        lenient().when(tenantConfigClient.getAdvancePaymentThreshold(any()))
                .thenReturn(Mono.just(new AdvancePaymentThreshold(new BigDecimal("500"), "USD")));
        lenient().when(fxConverter.convert(any(), anyString(), anyString(), any(), any()))
                .thenAnswer(inv -> Mono.just(inv.<BigDecimal>getArgument(0)));
    }

    @Test
    void create_belowThreshold_autoApproves() {
        UUID providerId = UUID.randomUUID();
        var request = new CreateAdvancePaymentRequest(
            providerId, null, new BigDecimal("250.00"), "USD",
            "EFT", "REF-001", "advance for medical supplies");

        StepVerifier.create(
                service.create(request, UUID.randomUUID().toString(), "actor@test.example")
                       .contextWrite(ctx -> ctx.put("TENANT_ID", UUID.randomUUID().toString()))
        )
                .assertNext(saved -> {
                    assertThat(saved.getProviderId()).isEqualTo(providerId);
                    assertThat(saved.getStatus()).isEqualTo("approved");
                    assertThat(saved.getType()).isEqualTo("ADVANCE");
                    assertThat(saved.getApprovedAt()).isNotNull();
                })
                .verifyComplete();

        verify(repository).save(any());
        verify(auditPublisher).publish(any());
        verify(eventPublisher).publishAdvanceApproved(any());
        verify(providerBalanceService).updateBalance(eq(providerId), eq("USD"),
                any(), any(), eq(new BigDecimal("250.00")), any(), any());
    }

    @Test
    void create_aboveThreshold_staysPending() {
        UUID providerId = UUID.randomUUID();
        var request = new CreateAdvancePaymentRequest(
            providerId, null, new BigDecimal("750.00"), "USD",
            "EFT", "REF-002", null);

        StepVerifier.create(
                service.create(request, UUID.randomUUID().toString(), "actor@test.example")
                       .contextWrite(ctx -> ctx.put("TENANT_ID", UUID.randomUUID().toString()))
        )
                .assertNext(saved -> {
                    assertThat(saved.getStatus()).isEqualTo("pending");
                    assertThat(saved.getApprovedBy()).isNull();
                    assertThat(saved.getApprovedAt()).isNull();
                })
                .verifyComplete();

        // pending advances do not touch balance or publish approval event
        verify(providerBalanceService, never()).updateBalance(any(), anyString(), any(), any(), any(), any(), any());
        verify(eventPublisher, never()).publishAdvanceApproved(any());
    }

    @Test
    void create_memberOnly_persists_andDoesNotTouchProviderBalance() {
        UUID memberId = UUID.randomUUID();
        var request = new CreateAdvancePaymentRequest(
            null, memberId, new BigDecimal("75.50"), "ZWG", null, null, null);

        StepVerifier.create(
                service.create(request, "system", "actor@test.example")
                       .contextWrite(ctx -> ctx.put("TENANT_ID", UUID.randomUUID().toString()))
        )
                .assertNext(saved -> {
                    assertThat(saved.getMemberId()).isEqualTo(memberId);
                    assertThat(saved.getProviderId()).isNull();
                    assertThat(saved.getStatus()).isEqualTo("approved");
                })
                .verifyComplete();

        // member advances intentionally do not touch provider_balances
        verify(providerBalanceService, never()).updateBalance(any(), anyString(), any(), any(), any(), any(), any());
    }

    @Test
    void create_neitherProviderNorMember_errors() {
        var request = new CreateAdvancePaymentRequest(
            null, null, new BigDecimal("100"), "USD", null, null, null);

        StepVerifier.create(
                service.create(request, "system", "actor@test.example")
                       .contextWrite(ctx -> ctx.put("TENANT_ID", UUID.randomUUID().toString()))
        )
                .expectError(IllegalArgumentException.class)
                .verify();

        verify(repository, never()).save(any());
    }

    @Test
    void approve_pending_flipsToApproved() {
        UUID advanceId = UUID.randomUUID();
        UUID recorder = UUID.randomUUID();
        var ap = advance(advanceId, recorder, "pending");
        when(repository.findById(advanceId)).thenReturn(Mono.just(ap));

        UUID approver = UUID.randomUUID();
        StepVerifier.create(service.approve(advanceId, approver.toString(), "hod@test.example")
                       .contextWrite(ctx -> ctx.put("TENANT_ID", UUID.randomUUID().toString())))
                .assertNext(saved -> {
                    assertThat(saved.getStatus()).isEqualTo("approved");
                    assertThat(saved.getApprovedBy()).isEqualTo(approver);
                })
                .verifyComplete();

        verify(providerBalanceService).updateBalance(any(), anyString(), any(), any(), any(), any(), any());
        verify(eventPublisher).publishAdvanceApproved(any());
    }

    @Test
    void approve_sameActorAsRecorder_errors() {
        UUID advanceId = UUID.randomUUID();
        UUID recorder = UUID.randomUUID();
        var ap = advance(advanceId, recorder, "pending");
        when(repository.findById(advanceId)).thenReturn(Mono.just(ap));

        StepVerifier.create(service.approve(advanceId, recorder.toString(), "self@test.example")
                       .contextWrite(ctx -> ctx.put("TENANT_ID", UUID.randomUUID().toString())))
                .expectError(IllegalStateException.class)
                .verify();
    }

    @Test
    void approve_nonPending_errors() {
        UUID advanceId = UUID.randomUUID();
        var ap = advance(advanceId, UUID.randomUUID(), "approved");
        when(repository.findById(advanceId)).thenReturn(Mono.just(ap));

        StepVerifier.create(service.approve(advanceId, UUID.randomUUID().toString(), "hod@test.example")
                       .contextWrite(ctx -> ctx.put("TENANT_ID", UUID.randomUUID().toString())))
                .expectError(IllegalStateException.class)
                .verify();
    }

    @Test
    void reverse_approved_createsCompensatingAndMarksOriginalReversed() {
        UUID advanceId = UUID.randomUUID();
        var ap = advance(advanceId, UUID.randomUUID(), "approved");
        when(repository.findById(advanceId)).thenReturn(Mono.just(ap));

        StepVerifier.create(service.reverse(advanceId, new ReverseAdvancePaymentRequest("clerk error"),
                        UUID.randomUUID().toString(), "hod@test.example")
                       .contextWrite(ctx -> ctx.put("TENANT_ID", UUID.randomUUID().toString())))
                .assertNext(compensating -> {
                    assertThat(compensating.getType()).isEqualTo("REVERSAL");
                    assertThat(compensating.getReversesAdvanceId()).isEqualTo(advanceId);
                    assertThat(compensating.getStatus()).isEqualTo("approved");
                    assertThat(compensating.getReference()).startsWith("REV-");
                })
                .verifyComplete();

        assertThat(ap.getStatus()).isEqualTo("reversed");
        verify(eventPublisher).publishAdvanceReversed(any(), any());
    }

    @Test
    void reverse_alreadyReversed_errors() {
        UUID advanceId = UUID.randomUUID();
        var ap = advance(advanceId, UUID.randomUUID(), "reversed");
        when(repository.findById(advanceId)).thenReturn(Mono.just(ap));

        StepVerifier.create(service.reverse(advanceId, new ReverseAdvancePaymentRequest("try twice"),
                        UUID.randomUUID().toString(), "hod@test.example")
                       .contextWrite(ctx -> ctx.put("TENANT_ID", UUID.randomUUID().toString())))
                .expectError(IllegalStateException.class)
                .verify();
    }

    @Test
    void findByProvider_delegatesToRepository() {
        UUID providerId = UUID.randomUUID();
        when(repository.findByProviderId(providerId)).thenReturn(Flux.empty());

        StepVerifier.create(service.findByProvider(providerId))
                .verifyComplete();

        verify(repository).findByProviderId(providerId);
    }

    @Test
    void findByMember_delegatesToRepository() {
        UUID memberId = UUID.randomUUID();
        when(repository.findByMemberId(memberId)).thenReturn(Flux.empty());

        StepVerifier.create(service.findByMember(memberId))
                .verifyComplete();

        verify(repository).findByMemberId(memberId);
    }

    private AdvancePayment advance(UUID id, UUID recorder, String status) {
        var ap = new AdvancePayment();
        ap.setId(id);
        ap.setType("ADVANCE");
        ap.setStatus(status);
        ap.setProviderId(UUID.randomUUID());
        ap.setAmount(new BigDecimal("300.00"));
        ap.setCurrencyCode("USD");
        ap.setReference("REF-X");
        ap.setRecordedAt(Instant.now());
        ap.setRecordedBy(recorder);
        return ap;
    }
}
