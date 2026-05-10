package com.medfund.finance.service;

import com.medfund.finance.dto.CreatePaymentRunRequest;
import com.medfund.finance.entity.PaymentRun;
import com.medfund.finance.repository.PaymentRepository;
import com.medfund.finance.repository.PaymentRunItemRepository;
import com.medfund.finance.repository.PaymentRunRepository;
import com.medfund.finance.repository.ProviderBalanceRepository;
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
class PaymentRunServiceTest {

    @Mock
    private PaymentRunRepository paymentRunRepository;

    @Mock
    private PaymentRunItemRepository paymentRunItemRepository;

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private ProviderBalanceRepository providerBalanceRepository;

    @Mock
    private AuditPublisher auditPublisher;

    @Mock
    private FinanceEventPublisher eventPublisher;

    @Mock
    private PaymentRunDecisionService decisionService;

    @InjectMocks
    private PaymentRunService paymentRunService;

    @Test
    void findAll_returnsRuns() {
        var r1 = createTestRun();
        var r2 = createTestRun();
        when(paymentRunRepository.findAllOrderByCreatedAtDesc()).thenReturn(Flux.just(r1, r2));

        StepVerifier.create(paymentRunService.findAll())
                .expectNext(r1)
                .expectNext(r2)
                .verifyComplete();

        verify(paymentRunRepository).findAllOrderByCreatedAtDesc();
    }

    @Test
    void create_validRequest_createsRun() {
        var request = new CreatePaymentRunRequest("USD", "Monthly provider payments");
        String actorId = UUID.randomUUID().toString();

        when(paymentRunRepository.existsByRunNumber(any())).thenReturn(Mono.just(false));
        when(paymentRunRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
                paymentRunService.create(request, actorId)
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
                .assertNext(saved -> {
                    assertThat(saved.getRunNumber()).startsWith("RUN-");
                    assertThat(saved.getStatus()).isEqualTo("draft");
                    assertThat(saved.getCurrencyCode()).isEqualTo("USD");
                    assertThat(saved.getDescription()).isEqualTo("Monthly provider payments");
                    assertThat(saved.getPaymentCount()).isEqualTo(0);
                    assertThat(saved.getCreatedBy()).isNotNull();
                    assertThat(saved.getCreatedAt()).isNotNull();
                })
                .verifyComplete();

        verify(paymentRunRepository).existsByRunNumber(any());
        verify(paymentRunRepository).save(any());
        verify(auditPublisher).publish(any());
    }

    @Test
    void execute_draftRun_setsStatusCompleted() {
        var run = createTestRun();
        String actorId = UUID.randomUUID().toString();

        when(paymentRunRepository.findById(run.getId())).thenReturn(Mono.just(run));
        when(paymentRunRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        // No items in this run — applyTenantRulesToItems and recomputeRunTotal both
        // call findByPaymentRunId; an empty Flux exercises the wiring without
        // dragging rule logic into this test.
        when(paymentRunItemRepository.findByPaymentRunId(run.getId())).thenReturn(Flux.empty());
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        when(eventPublisher.publishPaymentRunExecuted(any(), any(), anyInt())).thenReturn(Mono.empty());

        StepVerifier.create(
                paymentRunService.execute(run.getId(), actorId)
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
                .assertNext(saved -> {
                    assertThat(saved.getStatus()).isEqualTo("executed");
                    assertThat(saved.getExecutedAt()).isNotNull();
                    assertThat(saved.getExecutedBy()).isNotNull();
                    assertThat(saved.getUpdatedAt()).isNotNull();
                })
                .verifyComplete();

        verify(paymentRunRepository).findById(run.getId());
        // save called 3x: executing, recomputeRunTotal (totalAmount=0), executed
        verify(paymentRunRepository, times(3)).save(any());
        verify(auditPublisher).publish(any());
        verify(eventPublisher).publishPaymentRunExecuted(any(), any(), anyInt());
    }

    // ---- Helper ----

    private PaymentRun createTestRun() {
        var r = new PaymentRun();
        r.setId(UUID.randomUUID());
        r.setRunNumber("RUN-123456");
        r.setStatus("draft");
        r.setTotalAmount(BigDecimal.ZERO);
        r.setCurrencyCode("USD");
        r.setCreatedAt(Instant.now());
        r.setUpdatedAt(Instant.now());
        r.setCreatedBy(UUID.randomUUID());
        return r;
    }
}
