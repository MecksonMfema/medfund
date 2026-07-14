package com.medfund.finance.service;

import com.medfund.finance.dto.CreatePaymentRequest;
import com.medfund.finance.entity.Payment;
import com.medfund.finance.exception.PaymentNotFoundException;
import com.medfund.finance.repository.PaymentRepository;
import com.medfund.shared.audit.AuditPublisher;
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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private com.medfund.finance.repository.PaymentQueryRepository queryRepository;

    @Mock
    private AuditPublisher auditPublisher;

    @Mock
    private FinanceEventPublisher eventPublisher;

    @InjectMocks
    private PaymentService paymentService;

    @Test
    void findAll_returnsPayments() {
        var p1 = createTestPayment();
        var p2 = createTestPayment();
        when(paymentRepository.findAllOrderByCreatedAtDesc()).thenReturn(Flux.just(p1, p2));

        StepVerifier.create(paymentService.findAll())
                .expectNext(p1)
                .expectNext(p2)
                .verifyComplete();

        verify(paymentRepository).findAllOrderByCreatedAtDesc();
    }

    @Test
    void findById_existing_returnsPayment() {
        var payment = createTestPayment();
        when(paymentRepository.findById(payment.getId())).thenReturn(Mono.just(payment));

        StepVerifier.create(paymentService.findById(payment.getId()))
                .assertNext(result -> {
                    assertThat(result.getId()).isEqualTo(payment.getId());
                    assertThat(result.getPaymentNumber()).isEqualTo("PAY-123456");
                    assertThat(result.getStatus()).isEqualTo("pending");
                })
                .verifyComplete();

        verify(paymentRepository).findById(payment.getId());
    }

    @Test
    void findById_nonExisting_throwsNotFound() {
        var id = UUID.randomUUID();
        when(paymentRepository.findById(id)).thenReturn(Mono.empty());

        StepVerifier.create(paymentService.findById(id))
                .expectError(PaymentNotFoundException.class)
                .verify();

        verify(paymentRepository).findById(id);
    }

    @Test
    void create_validRequest_createsPayment() {
        var request = new CreatePaymentRequest(
                UUID.randomUUID(),
                new BigDecimal("5000.00"),
                "USD",
                "provider_payment",
                "bank_transfer",
                "REF-001"
        );
        String actorId = UUID.randomUUID().toString();

        when(paymentRepository.existsByPaymentNumber(any())).thenReturn(Mono.just(false));
        when(paymentRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        when(eventPublisher.publishPaymentCreated(any(), any(), any())).thenReturn(Mono.empty());

        StepVerifier.create(
                paymentService.create(request, actorId, "actor@test.example")
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
                .assertNext(saved -> {
                    assertThat(saved.getPaymentNumber()).startsWith("PAY-");
                    assertThat(saved.getStatus()).isEqualTo("pending");
                    assertThat(saved.getAmount()).isEqualByComparingTo(new BigDecimal("5000.00"));
                    assertThat(saved.getCurrencyCode()).isEqualTo("USD");
                    assertThat(saved.getPaymentType()).isEqualTo("provider_payment");
                    assertThat(saved.getPaymentMethod()).isEqualTo("bank_transfer");
                    assertThat(saved.getReference()).isEqualTo("REF-001");
                    assertThat(saved.getProviderId()).isEqualTo(request.providerId());
                    assertThat(saved.getCreatedBy()).isNotNull();
                })
                .verifyComplete();

        verify(paymentRepository).existsByPaymentNumber(any());
        verify(paymentRepository).save(any());
        verify(auditPublisher).publish(any());
        verify(eventPublisher).publishPaymentCreated(any(), any(), any());
    }

    @Test
    void markPaid_existingPayment_setsStatusPaid() {
        var payment = createTestPayment();
        String actorId = UUID.randomUUID().toString();

        when(paymentRepository.findById(payment.getId())).thenReturn(Mono.just(payment));
        when(paymentRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        when(eventPublisher.publishPaymentCommitted(any(), any(), any(), any()))
            .thenReturn(Mono.empty());

        StepVerifier.create(
                paymentService.markPaid(payment.getId(), actorId, "actor@test.example")
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
                .assertNext(saved -> {
                    assertThat(saved.getStatus()).isEqualTo("paid");
                    assertThat(saved.getPaidAt()).isNotNull();
                    assertThat(saved.getUpdatedAt()).isNotNull();
                })
                .verifyComplete();

        verify(paymentRepository).findById(payment.getId());
        verify(paymentRepository).save(any());
        verify(auditPublisher).publish(any());
        verify(eventPublisher).publishPaymentCommitted(any(), any(), any(), any());
    }

    @Test
    void cancel_pending_flipsToCancelled() {
        var payment = createTestPayment();
        payment.setStatus("pending");
        when(paymentRepository.findById(payment.getId())).thenReturn(Mono.just(payment));
        when(paymentRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
                paymentService.cancel(payment.getId(), UUID.randomUUID().toString(), "actor@test.example")
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
                .assertNext(saved -> assertThat(saved.getStatus()).isEqualTo("cancelled"))
                .verifyComplete();
    }

    @Test
    void cancel_paid_errors() {
        var payment = createTestPayment();
        payment.setStatus("paid");
        when(paymentRepository.findById(payment.getId())).thenReturn(Mono.just(payment));

        StepVerifier.create(
                paymentService.cancel(payment.getId(), UUID.randomUUID().toString(), "actor@test.example")
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
                .expectError(IllegalStateException.class)
                .verify();

        verify(paymentRepository, never()).save(any());
    }

    @Test
    void cancel_alreadyCancelled_isIdempotent() {
        var payment = createTestPayment();
        payment.setStatus("cancelled");
        when(paymentRepository.findById(payment.getId())).thenReturn(Mono.just(payment));

        StepVerifier.create(
                paymentService.cancel(payment.getId(), UUID.randomUUID().toString(), "actor@test.example")
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
                .assertNext(saved -> assertThat(saved.getStatus()).isEqualTo("cancelled"))
                .verifyComplete();

        verify(paymentRepository, never()).save(any());
    }

    // ---- Helper ----

    private Payment createTestPayment() {
        var p = new Payment();
        p.setId(UUID.randomUUID());
        p.setPaymentNumber("PAY-123456");
        p.setProviderId(UUID.randomUUID());
        p.setAmount(new BigDecimal("5000.00"));
        p.setCurrencyCode("USD");
        p.setPaymentType("provider_payment");
        p.setStatus("pending");
        p.setCreatedAt(Instant.now());
        p.setUpdatedAt(Instant.now());
        p.setCreatedBy(UUID.randomUUID());
        p.setUpdatedBy(UUID.randomUUID());
        return p;
    }
}
