package com.medfund.finance.service;

import com.medfund.finance.dto.CreateAdjustmentRequest;
import com.medfund.finance.entity.Adjustment;
import com.medfund.finance.exception.AdjustmentNotFoundException;
import com.medfund.finance.repository.AdjustmentRepository;
import com.medfund.shared.audit.AuditPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdjustmentServiceTest {

    @Mock
    private AdjustmentRepository adjustmentRepository;

    @Mock
    private AuditPublisher auditPublisher;

    @Mock
    private FinanceEventPublisher eventPublisher;

    @InjectMocks
    private AdjustmentService adjustmentService;

    @Test
    void findById_existing_returnsAdjustment() {
        var adjustment = createTestAdjustment();
        when(adjustmentRepository.findById(adjustment.getId())).thenReturn(Mono.just(adjustment));

        StepVerifier.create(adjustmentService.findById(adjustment.getId()))
                .assertNext(result -> {
                    assertThat(result.getId()).isEqualTo(adjustment.getId());
                    assertThat(result.getAdjustmentNumber()).isEqualTo("ADJ-123456");
                    assertThat(result.getStatus()).isEqualTo("pending");
                })
                .verifyComplete();

        verify(adjustmentRepository).findById(adjustment.getId());
    }

    @Test
    void findById_nonExisting_throwsNotFound() {
        var id = UUID.randomUUID();
        when(adjustmentRepository.findById(id)).thenReturn(Mono.empty());

        StepVerifier.create(adjustmentService.findById(id))
                .expectError(AdjustmentNotFoundException.class)
                .verify();

        verify(adjustmentRepository).findById(id);
    }

    @Test
    void create_validRequest_createsAdjustment() {
        var request = new CreateAdjustmentRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "credit",
                new BigDecimal("1500.00"),
                "USD",
                "Overpayment correction"
        );
        String actorId = UUID.randomUUID().toString();

        when(adjustmentRepository.existsByAdjustmentNumber(any())).thenReturn(Mono.just(false));
        when(adjustmentRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
                adjustmentService.create(request, actorId, "actor@test.example")
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
                .assertNext(saved -> {
                    assertThat(saved.getAdjustmentNumber()).startsWith("ADJ-");
                    assertThat(saved.getStatus()).isEqualTo("pending");
                    assertThat(saved.getAmount()).isEqualByComparingTo(new BigDecimal("1500.00"));
                    assertThat(saved.getCurrencyCode()).isEqualTo("USD");
                    assertThat(saved.getAdjustmentType()).isEqualTo("credit");
                    assertThat(saved.getReason()).isEqualTo("Overpayment correction");
                    assertThat(saved.getProviderId()).isEqualTo(request.providerId());
                    assertThat(saved.getMemberId()).isEqualTo(request.memberId());
                    assertThat(saved.getCreatedBy()).isNotNull();
                })
                .verifyComplete();

        verify(adjustmentRepository).existsByAdjustmentNumber(any());
        verify(adjustmentRepository).save(any());
        verify(auditPublisher).publish(any());
    }

    @Test
    void approve_existingAdjustment_setsStatusApproved() {
        var adjustment = createTestAdjustment();
        String actorId = UUID.randomUUID().toString();

        when(adjustmentRepository.findById(adjustment.getId())).thenReturn(Mono.just(adjustment));
        when(adjustmentRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
                adjustmentService.approve(adjustment.getId(), actorId, "actor@test.example")
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
                .assertNext(saved -> {
                    assertThat(saved.getStatus()).isEqualTo("approved");
                    assertThat(saved.getApprovedBy()).isNotNull();
                    assertThat(saved.getApprovedAt()).isNotNull();
                    assertThat(saved.getUpdatedAt()).isNotNull();
                })
                .verifyComplete();

        verify(adjustmentRepository).findById(adjustment.getId());
        verify(adjustmentRepository).save(any());
        verify(auditPublisher).publish(any());
    }

    @Test
    void apply_existingAdjustment_setsStatusApplied() {
        var adjustment = createTestAdjustment();
        adjustment.setStatus("approved");
        String actorId = UUID.randomUUID().toString();

        when(adjustmentRepository.findById(adjustment.getId())).thenReturn(Mono.just(adjustment));
        when(adjustmentRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        when(eventPublisher.publishAdjustmentApplied(any(), any(), any())).thenReturn(Mono.empty());

        StepVerifier.create(
                adjustmentService.apply(adjustment.getId(), actorId, "actor@test.example")
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
                .assertNext(saved -> {
                    assertThat(saved.getStatus()).isEqualTo("applied");
                    assertThat(saved.getUpdatedAt()).isNotNull();
                })
                .verifyComplete();

        verify(adjustmentRepository).findById(adjustment.getId());
        verify(adjustmentRepository).save(any());
        verify(auditPublisher).publish(any());
        verify(eventPublisher).publishAdjustmentApplied(any(), any(), any());
    }

    @Test
    void cancel_pending_flipsToCancelled() {
        var adjustment = createTestAdjustment();
        adjustment.setStatus("pending");
        when(adjustmentRepository.findById(adjustment.getId())).thenReturn(Mono.just(adjustment));
        when(adjustmentRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
                adjustmentService.cancel(adjustment.getId(), UUID.randomUUID().toString(), "actor@test.example")
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
                .assertNext(saved -> assertThat(saved.getStatus()).isEqualTo("cancelled"))
                .verifyComplete();
    }

    @Test
    void cancel_approved_flipsToCancelled() {
        var adjustment = createTestAdjustment();
        adjustment.setStatus("approved");
        when(adjustmentRepository.findById(adjustment.getId())).thenReturn(Mono.just(adjustment));
        when(adjustmentRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
                adjustmentService.cancel(adjustment.getId(), UUID.randomUUID().toString(), "actor@test.example")
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
                .assertNext(saved -> assertThat(saved.getStatus()).isEqualTo("cancelled"))
                .verifyComplete();
    }

    @Test
    void cancel_applied_errors() {
        var adjustment = createTestAdjustment();
        adjustment.setStatus("applied");
        when(adjustmentRepository.findById(adjustment.getId())).thenReturn(Mono.just(adjustment));

        StepVerifier.create(
                adjustmentService.cancel(adjustment.getId(), UUID.randomUUID().toString(), "actor@test.example")
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
                .expectError(IllegalStateException.class)
                .verify();

        verify(adjustmentRepository, never()).save(any());
    }

    @Test
    void cancel_alreadyCancelled_isIdempotent() {
        var adjustment = createTestAdjustment();
        adjustment.setStatus("cancelled");
        when(adjustmentRepository.findById(adjustment.getId())).thenReturn(Mono.just(adjustment));

        StepVerifier.create(
                adjustmentService.cancel(adjustment.getId(), UUID.randomUUID().toString(), "actor@test.example")
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
                .assertNext(saved -> assertThat(saved.getStatus()).isEqualTo("cancelled"))
                .verifyComplete();

        verify(adjustmentRepository, never()).save(any());
    }

    // ---- Helper ----

    private Adjustment createTestAdjustment() {
        var a = new Adjustment();
        a.setId(UUID.randomUUID());
        a.setAdjustmentNumber("ADJ-123456");
        a.setProviderId(UUID.randomUUID());
        a.setMemberId(UUID.randomUUID());
        a.setAdjustmentType("credit");
        a.setAmount(new BigDecimal("1500.00"));
        a.setCurrencyCode("USD");
        a.setReason("Overpayment correction");
        a.setStatus("pending");
        a.setCreatedAt(Instant.now());
        a.setUpdatedAt(Instant.now());
        a.setCreatedBy(UUID.randomUUID());
        return a;
    }
}
