package com.medfund.finance.service;

import com.medfund.finance.client.FxConverter;
import com.medfund.finance.dto.CreatePaymentRunRequest;
import com.medfund.finance.entity.PaymentRun;
import com.medfund.finance.repository.AdvancePaymentApplicationRepository;
import com.medfund.finance.repository.AdvancePaymentBalanceRepository;
import com.medfund.finance.repository.AdvancePaymentRepository;
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
    private AdvancePaymentRepository advancePaymentRepository;

    @Mock
    private AdvancePaymentBalanceRepository advanceBalanceRepository;

    @Mock
    private AdvancePaymentApplicationRepository advanceApplicationRepository;

    @Mock
    private FxConverter fxConverter;

    @Mock
    private AuditPublisher auditPublisher;

    @Mock
    private FinanceEventPublisher eventPublisher;

    @Mock
    private PaymentRunDecisionService decisionService;

    @Mock
    private PaymentRunGenerator paymentRunGenerator;

    @Mock
    private PaymentAdviceService paymentAdviceService;

    @Mock
    private org.springframework.r2dbc.core.DatabaseClient databaseClient;

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
        var request = new CreatePaymentRunRequest("USD", "Monthly provider payments", "PROVIDER");
        String actorId = UUID.randomUUID().toString();

        when(paymentRunRepository.existsByRunNumber(any())).thenReturn(Mono.just(false));
        when(paymentRunRepository.save(any())).thenAnswer(inv -> {
            PaymentRun saved = inv.getArgument(0);
            if (saved.getId() == null) saved.setId(UUID.randomUUID());
            return Mono.just(saved);
        });
        when(paymentRunGenerator.populate(any())).thenReturn(Mono.just(0));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        when(eventPublisher.publishPaymentRunCreated(any(), any(), any(), any(), anyInt()))
            .thenReturn(Mono.empty());

        StepVerifier.create(
                paymentRunService.create(request, actorId, "actor@test.example")
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
        verify(paymentRunRepository, times(2)).save(any());  // once for header, once for updated count
        verify(paymentRunGenerator).populate(any());
        verify(auditPublisher).publish(any());
        verify(eventPublisher).publishPaymentRunCreated(any(), any(), any(), any(), anyInt());
    }

    @Test
    void create_populateReturnsMultipleItems_countRecordedOnRun() {
        var request = new CreatePaymentRunRequest("USD", "With populated items", "PROVIDER");
        String actorId = UUID.randomUUID().toString();

        when(paymentRunRepository.existsByRunNumber(any())).thenReturn(Mono.just(false));
        when(paymentRunRepository.save(any())).thenAnswer(inv -> {
            PaymentRun saved = inv.getArgument(0);
            if (saved.getId() == null) saved.setId(UUID.randomUUID());
            return Mono.just(saved);
        });
        when(paymentRunGenerator.populate(any())).thenReturn(Mono.just(3));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        when(eventPublisher.publishPaymentRunCreated(any(), any(), any(), any(), anyInt()))
            .thenReturn(Mono.empty());

        StepVerifier.create(
                paymentRunService.create(request, actorId, "actor@test.example")
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
                .assertNext(saved -> assertThat(saved.getPaymentCount()).isEqualTo(3))
                .verifyComplete();

        verify(paymentRunGenerator).populate(any());
    }

    @Test
    void execute_draftRun_setsStatusCompleted() {
        var run = createTestRun();
        String actorId = UUID.randomUUID().toString();

        when(paymentRunRepository.findById(run.getId())).thenReturn(Mono.just(run));
        when(paymentRunRepository.save(any())).thenAnswer(inv -> {
            PaymentRun saved = inv.getArgument(0);
            if (saved.getId() == null) saved.setId(UUID.randomUUID());
            return Mono.just(saved);
        });
        // No items in this run — applyTenantRulesToItems and recomputeRunTotal both
        // call findByPaymentRunId; an empty Flux exercises the wiring without
        // dragging rule logic into this test.
        when(paymentRunItemRepository.findByPaymentRunId(run.getId())).thenReturn(Flux.empty());
        when(paymentAdviceService.generateAdvicesForRun(run.getId())).thenReturn(Flux.empty());
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        when(eventPublisher.publishPaymentRunExecuted(any(), any(), anyInt())).thenReturn(Mono.empty());

        StepVerifier.create(
                paymentRunService.execute(run.getId(), actorId, "actor@test.example")
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
        // save called 4x: executing, recomputeRunTotal (totalAmount=0),
        // snapshotCarryOut (carriedOut=0, always fires), executed. The
        // settlement-date snapshot short-circuits on the empty-items path
        // and does not save.
        verify(paymentRunRepository, times(4)).save(any());
        verify(paymentAdviceService).generateAdvicesForRun(run.getId());
        verify(auditPublisher).publish(any());
        verify(eventPublisher).publishPaymentRunExecuted(any(), any(), anyInt());
    }

    @Test
    void approve_draft_flipsToApproved() {
        var run = createTestRun();
        when(paymentRunRepository.findById(run.getId())).thenReturn(Mono.just(run));
        when(paymentRunRepository.save(any())).thenAnswer(inv -> {
            PaymentRun saved = inv.getArgument(0);
            if (saved.getId() == null) saved.setId(UUID.randomUUID());
            return Mono.just(saved);
        });
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        when(eventPublisher.publishPaymentRunApproved(any(), any(), any())).thenReturn(Mono.empty());

        StepVerifier.create(
                paymentRunService.approve(run.getId(), UUID.randomUUID().toString(), "actor@test.example")
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
                .assertNext(saved -> assertThat(saved.getStatus()).isEqualTo("approved"))
                .verifyComplete();

        verify(eventPublisher).publishPaymentRunApproved(any(), any(), any());
    }

    @Test
    void approve_nonDraft_errors() {
        var run = createTestRun();
        run.setStatus("approved");
        when(paymentRunRepository.findById(run.getId())).thenReturn(Mono.just(run));

        StepVerifier.create(
                paymentRunService.approve(run.getId(), UUID.randomUUID().toString(), "actor@test.example")
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
                .expectError(IllegalStateException.class)
                .verify();

        verify(paymentRunRepository, never()).save(any());
    }

    @Test
    void cancel_draft_flipsToCancelled() {
        var run = createTestRun();
        when(paymentRunRepository.findById(run.getId())).thenReturn(Mono.just(run));
        when(paymentRunRepository.save(any())).thenAnswer(inv -> {
            PaymentRun saved = inv.getArgument(0);
            if (saved.getId() == null) saved.setId(UUID.randomUUID());
            return Mono.just(saved);
        });
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        when(eventPublisher.publishPaymentRunCancelled(any(), any(), any())).thenReturn(Mono.empty());

        StepVerifier.create(
                paymentRunService.cancel(run.getId(), UUID.randomUUID().toString(), "actor@test.example")
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
                .assertNext(saved -> assertThat(saved.getStatus()).isEqualTo("cancelled"))
                .verifyComplete();
    }

    @Test
    void cancel_executed_errors() {
        var run = createTestRun();
        run.setStatus("executed");
        when(paymentRunRepository.findById(run.getId())).thenReturn(Mono.just(run));

        StepVerifier.create(
                paymentRunService.cancel(run.getId(), UUID.randomUUID().toString(), "actor@test.example")
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
                .expectError(IllegalStateException.class)
                .verify();

        verify(paymentRunRepository, never()).save(any());
    }

    @Test
    void cancel_alreadyCancelled_isIdempotent() {
        var run = createTestRun();
        run.setStatus("cancelled");
        when(paymentRunRepository.findById(run.getId())).thenReturn(Mono.just(run));

        StepVerifier.create(
                paymentRunService.cancel(run.getId(), UUID.randomUUID().toString(), "actor@test.example")
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
                .assertNext(saved -> assertThat(saved.getStatus()).isEqualTo("cancelled"))
                .verifyComplete();

        verify(paymentRunRepository, never()).save(any());
    }

    @Test
    void execute_withAdvanceOffset_writesApplicationAndFlipsAdvance() {
        var run = createTestRun();
        UUID providerId = UUID.randomUUID();
        UUID advanceId  = UUID.randomUUID();

        var item = new com.medfund.finance.entity.PaymentRunItem();
        item.setId(UUID.randomUUID());
        item.setPaymentRunId(run.getId());
        item.setProviderId(providerId);
        item.setPaymentId(UUID.randomUUID());
        item.setAmount(new BigDecimal("300.00"));
        item.setCurrencyCode("USD");
        item.setStatus("pending");

        var advance = new com.medfund.finance.entity.AdvancePayment();
        advance.setId(advanceId);
        advance.setProviderId(providerId);
        advance.setAmount(new BigDecimal("500.00"));
        advance.setCurrencyCode("USD");
        advance.setStatus("approved");
        advance.setType("ADVANCE");

        when(paymentRunRepository.findById(run.getId())).thenReturn(Mono.just(run));
        when(paymentRunRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(paymentRunItemRepository.findByPaymentRunId(run.getId())).thenReturn(Flux.just(item));
        when(paymentRunItemRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(paymentAdviceService.generateAdvicesForRun(run.getId())).thenReturn(Flux.empty());
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        when(eventPublisher.publishPaymentRunExecuted(any(), any(), anyInt())).thenReturn(Mono.empty());

        // Advance balance lookup returns $500 USD outstanding.
        when(advanceBalanceRepository.findOutstandingByProvider(providerId)).thenReturn(
            Flux.just(new com.medfund.finance.repository.OutstandingAdvanceBalance("USD", new BigDecimal("500.00"))));

        // The rule engine "fires" — mock the DecisionService to simulate a 100% withhold
        // by dropping the item amount to zero and returning a fact with the withholdAmount.
        when(decisionService.decide(any(), any())).thenAnswer(inv -> {
            com.medfund.finance.entity.PaymentRunItem it = inv.getArgument(0);
            it.setAmount(BigDecimal.ZERO);
            it.setStatus("withheld");
            return Mono.just(new com.medfund.rules.fact.PaymentRunFact());
        });

        // FIFO drawdown: oldest open advance = the one above; remaining before + after.
        when(advancePaymentRepository.findOldestOpenForProvider(providerId, "USD"))
                .thenReturn(Mono.just(advance));
        when(advanceBalanceRepository.remainingOn(advanceId))
                .thenReturn(Mono.just(new BigDecimal("500.00")))    // remaining while writing app row
                .thenReturn(Mono.just(new BigDecimal("200.00")));   // remaining after (500 - 300)

        when(advanceApplicationRepository.save(any())).thenAnswer(inv -> {
            com.medfund.finance.entity.AdvancePaymentApplication saved = inv.getArgument(0);
            if (saved.getId() == null) saved.setId(UUID.randomUUID());
            return Mono.just(saved);
        });
        when(eventPublisher.publishAdvanceApplied(any())).thenReturn(Mono.empty());
        // advancePaymentRepository.save(...) is only called when the advance is fully
        // drawn down (remaining <= 0). In this scenario 200 remain, so save is not invoked.

        // snapshotSettlementDate uses the DatabaseClient directly to project
        // BOOL_AND(status='paid') across items; stub the whole fluent chain so
        // the empty result short-circuits without NPE'ing.
        var stubSpec = org.mockito.Mockito.mock(org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec.class);
        var stubFetch = org.mockito.Mockito.mock(org.springframework.r2dbc.core.FetchSpec.class);
        when(databaseClient.sql(anyString())).thenReturn(stubSpec);
        when(stubSpec.bind(anyString(), any())).thenReturn(stubSpec);
        when(stubSpec.fetch()).thenReturn(stubFetch);
        when(stubFetch.one()).thenReturn(Mono.empty());

        StepVerifier.create(
                paymentRunService.execute(run.getId(), UUID.randomUUID().toString(), "actor@test.example")
                        .contextWrite(ctx -> ctx.put("TENANT_ID", UUID.randomUUID().toString()))
        )
                .assertNext(saved -> assertThat(saved.getStatus()).isEqualTo("executed"))
                .verifyComplete();

        // Application row was written with the withheld amount.
        org.mockito.ArgumentCaptor<com.medfund.finance.entity.AdvancePaymentApplication> appCap =
                org.mockito.ArgumentCaptor.forClass(com.medfund.finance.entity.AdvancePaymentApplication.class);
        verify(advanceApplicationRepository).save(appCap.capture());
        assertThat(appCap.getValue().getAmountApplied()).isEqualByComparingTo("300.00");
        assertThat(appCap.getValue().getAdvancePaymentId()).isEqualTo(advanceId);
        assertThat(appCap.getValue().getPaymentRunItemId()).isEqualTo(item.getId());
        verify(eventPublisher).publishAdvanceApplied(any());
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
