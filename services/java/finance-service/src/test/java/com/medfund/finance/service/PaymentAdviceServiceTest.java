package com.medfund.finance.service;

import com.medfund.finance.dto.PageResponse;
import com.medfund.finance.dto.PaymentAdviceFilterParams;
import com.medfund.finance.dto.PaymentAdviceRowResponse;
import com.medfund.finance.entity.PaymentAdviceLine;
import com.medfund.finance.entity.PaymentAdviceRecord;
import com.medfund.finance.entity.PaymentRun;
import com.medfund.finance.entity.PaymentRunItem;
import com.medfund.finance.repository.PaymentAdviceLineRepository;
import com.medfund.finance.repository.PaymentAdviceQueryRepository;
import com.medfund.finance.repository.PaymentAdviceRecordRepository;
import com.medfund.finance.repository.PaymentRunItemRepository;
import com.medfund.finance.repository.PaymentRunRepository;
import com.medfund.shared.audit.AuditPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.FetchSpec;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PaymentAdviceServiceTest {

    @Mock private PaymentRunRepository paymentRunRepository;
    @Mock private PaymentRunItemRepository paymentRunItemRepository;
    @Mock private PaymentAdviceRecordRepository adviceRepository;
    @Mock private PaymentAdviceLineRepository adviceLineRepository;
    @Mock private PaymentAdviceQueryRepository queryRepository;
    @Mock private DatabaseClient db;
    @Mock private FinanceEventPublisher eventPublisher;
    @Mock private AuditPublisher auditPublisher;

    @Mock private DatabaseClient.GenericExecuteSpec spec;
    @Mock private FetchSpec<java.util.Map<String, Object>> fetch;

    @InjectMocks private PaymentAdviceService service;

    @Test
    void generateAdvicesForRun_emptyItems_yieldsNoAdvices() {
        UUID runId = UUID.randomUUID();
        PaymentRun run = mkRun(runId, "USD");

        when(paymentRunRepository.findById(runId)).thenReturn(Mono.just(run));
        when(paymentRunRepository.findMostRecentPriorExecuted(anyString(), any(), any()))
            .thenReturn(Mono.empty());
        when(paymentRunItemRepository.findByPaymentRunId(runId)).thenReturn(Flux.empty());

        StepVerifier.create(service.generateAdvicesForRun(runId)
                .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant")))
            .verifyComplete();
    }

    @Test
    void generateAdvicesForRun_singleProviderItem_persistsHeaderAndPublishes() {
        UUID runId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        PaymentRun run = mkRun(runId, "USD");

        var item = new PaymentRunItem();
        item.setId(UUID.randomUUID());
        item.setPaymentRunId(runId);
        item.setPayeeType("PROVIDER");
        item.setProviderId(providerId);
        item.setAmount(new BigDecimal("500.00"));
        item.setCurrencyCode("USD");

        when(paymentRunRepository.findById(runId)).thenReturn(Mono.just(run));
        when(paymentRunRepository.findMostRecentPriorExecuted(anyString(), any(), any()))
            .thenReturn(Mono.empty());
        when(paymentRunItemRepository.findByPaymentRunId(runId)).thenReturn(Flux.just(item));

        // Every SQL query returns empty (no claims, no ctc, no advance, no tax, no shortfall, no carry)
        stubEmptyDb();

        // Save advice returns the record with an id
        when(adviceRepository.save(any())).thenAnswer(inv -> {
            PaymentAdviceRecord r = inv.getArgument(0);
            if (r.getId() == null) r.setId(UUID.randomUUID());
            return Mono.just(r);
        });
        when(adviceLineRepository.saveAll(any(Iterable.class))).thenReturn(Flux.empty());
        when(adviceRepository.findByPaymentRunIdAndProviderId(any(), any()))
            .thenAnswer(inv -> {
                var r = new PaymentAdviceRecord();
                r.setId(UUID.randomUUID());
                r.setAdviceNumber("ADV-000001");
                r.setPaymentRunId(inv.getArgument(0));
                r.setProviderId(inv.getArgument(1));
                r.setPayeeType("PROVIDER");
                r.setNetDueAmount(BigDecimal.ZERO);
                return Mono.just(r);
            });
        when(eventPublisher.publishAdviceGenerated(any(), any())).thenReturn(Mono.empty());
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(service.generateAdvicesForRun(runId)
                .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant")))
            .assertNext(advice -> {
                assertThat(advice.payeeType()).isEqualTo("PROVIDER");
                assertThat(advice.providerId()).isEqualTo(providerId);
                assertThat(advice.memberId()).isNull();
                assertThat(advice.netDueAmount()).isEqualByComparingTo("0");
                assertThat(advice.lines()).isEmpty();
            })
            .verifyComplete();

        ArgumentCaptor<PaymentAdviceRecord> cap = ArgumentCaptor.forClass(PaymentAdviceRecord.class);
        verify(adviceRepository).save(cap.capture());
        assertThat(cap.getValue().getPayeeType()).isEqualTo("PROVIDER");
        assertThat(cap.getValue().getProviderId()).isEqualTo(providerId);
        assertThat(cap.getValue().getMemberId()).isNull();
        assertThat(cap.getValue().getAdviceNumber()).startsWith("ADV-");
    }

    @Test
    void searchPaged_delegatesToQueryRepositoryAndClampsSize() {
        var row = new PaymentAdviceRowResponse(
                UUID.randomUUID(), "ADV-000001", UUID.randomUUID(), "RUN-000001",
                "PROVIDER", UUID.randomUUID(), null, "Acme Clinic",
                "USD", new BigDecimal("250.00"), 3, "generated",
                Instant.now(), Instant.now().minusSeconds(30 * 86400), Instant.now(),
                BigDecimal.ZERO, new BigDecimal("250.00"), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("250.00"), Instant.now());

        // size 999 must be clamped to 200; page 0 means offset 0.
        when(queryRepository.search(any(PaymentAdviceFilterParams.class), org.mockito.ArgumentMatchers.eq(200), org.mockito.ArgumentMatchers.eq(0)))
            .thenReturn(Flux.just(row));
        when(queryRepository.count(any(PaymentAdviceFilterParams.class)))
            .thenReturn(Mono.just(1L));

        var params = new PaymentAdviceFilterParams(
                null, null, null, null, null, null, null, null, null,
                "issuedAt", "desc", 0, 999);

        StepVerifier.create(service.searchPaged(params))
            .assertNext((PageResponse<PaymentAdviceRowResponse> page) -> {
                assertThat(page.content()).hasSize(1);
                assertThat(page.total()).isEqualTo(1L);
                assertThat(page.page()).isZero();
                assertThat(page.size()).isEqualTo(200);
                assertThat(page.content().get(0).payeeName()).isEqualTo("Acme Clinic");
                assertThat(page.content().get(0).runNumber()).isEqualTo("RUN-000001");
            })
            .verifyComplete();
    }

    @Test
    void generateAdvicesForRun_firstTimePayee_windowStartsAtEpoch() {
        // A payee that has never received a payment advice before must see
        // period_start_at = EPOCH on their first advice, so every historical
        // CLAIM_PAID / NOTE / CTC line since forever is enumerated and the
        // advice net_due tallies to PaymentRunItem.amount (which drains the
        // period-agnostic outstanding_balance). Guards the silent
        // under-reporting gap fixed by resolvePayeePriorPeriodEnd.
        UUID runId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        PaymentRun run = mkRun(runId, "USD");

        var item = new PaymentRunItem();
        item.setId(UUID.randomUUID());
        item.setPaymentRunId(runId);
        item.setPayeeType("PROVIDER");
        item.setProviderId(providerId);
        item.setAmount(new BigDecimal("500.00"));
        item.setCurrencyCode("USD");

        when(paymentRunRepository.findById(runId)).thenReturn(Mono.just(run));
        when(paymentRunRepository.findMostRecentPriorExecuted(anyString(), any(), any()))
            .thenReturn(Mono.empty());
        when(paymentRunItemRepository.findByPaymentRunId(runId)).thenReturn(Flux.just(item));

        // All ledger + boundary queries empty — the payee has never been
        // touched by a prior advice. resolvePayeePriorPeriodEnd hits the
        // same stub and returns EPOCH via defaultIfEmpty.
        stubEmptyDb();
        stubAdviceSaveAndPublish();

        StepVerifier.create(service.generateAdvicesForRun(runId)
                .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant")))
            .assertNext(advice -> assertThat(advice.periodStartAt()).isEqualTo(Instant.EPOCH))
            .verifyComplete();

        ArgumentCaptor<PaymentAdviceRecord> cap = ArgumentCaptor.forClass(PaymentAdviceRecord.class);
        verify(adviceRepository).save(cap.capture());
        assertThat(cap.getValue().getPeriodStartAt()).isEqualTo(Instant.EPOCH);
        assertThat(cap.getValue().getCarriedInAmount()).isEqualByComparingTo("0");
    }

    @Test
    void generateAdvicesForRun_repeatPayee_windowStartsAtPriorAdvicePeriodEnd() {
        // A payee who received a prior advice in the same currency must see
        // period_start_at = that prior advice's period_end_at — not the
        // tenant's prior run boundary. Guards against the window growing
        // wider than needed for repeat payees.
        UUID runId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        Instant priorBoundary = Instant.parse("2026-06-01T00:00:00Z");
        PaymentRun run = mkRun(runId, "USD");

        var item = new PaymentRunItem();
        item.setId(UUID.randomUUID());
        item.setPaymentRunId(runId);
        item.setPayeeType("PROVIDER");
        item.setProviderId(providerId);
        item.setAmount(new BigDecimal("120.00"));
        item.setCurrencyCode("USD");

        when(paymentRunRepository.findById(runId)).thenReturn(Mono.just(run));
        when(paymentRunRepository.findMostRecentPriorExecuted(anyString(), any(), any()))
            .thenReturn(Mono.empty());
        when(paymentRunItemRepository.findByPaymentRunId(runId)).thenReturn(Flux.just(item));

        // Route the payee-boundary query to return priorBoundary; all other
        // queries return empty (no ledger lines, no carry-forward — that's
        // fine, we only care about the window boundary here).
        stubDbWithBoundary(priorBoundary);
        stubAdviceSaveAndPublish();

        StepVerifier.create(service.generateAdvicesForRun(runId)
                .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant")))
            .assertNext(advice -> assertThat(advice.periodStartAt()).isEqualTo(priorBoundary))
            .verifyComplete();

        ArgumentCaptor<PaymentAdviceRecord> cap = ArgumentCaptor.forClass(PaymentAdviceRecord.class);
        verify(adviceRepository).save(cap.capture());
        assertThat(cap.getValue().getPeriodStartAt()).isEqualTo(priorBoundary);
    }

    @Test
    void regenerateAdvicesForRun_deletesFirstThenBuilds() {
        UUID runId = UUID.randomUUID();
        PaymentRun run = mkRun(runId, "USD");

        when(adviceRepository.deleteByPaymentRunId(runId)).thenReturn(Mono.empty());
        when(paymentRunRepository.findById(runId)).thenReturn(Mono.just(run));
        when(paymentRunRepository.findMostRecentPriorExecuted(anyString(), any(), any()))
            .thenReturn(Mono.empty());
        when(paymentRunItemRepository.findByPaymentRunId(runId)).thenReturn(Flux.empty());

        StepVerifier.create(service.regenerateAdvicesForRun(runId)
                .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant")))
            .verifyComplete();

        verify(adviceRepository).deleteByPaymentRunId(runId);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubEmptyDb() {
        when(db.sql(anyString())).thenReturn(spec);
        when(spec.bind(anyString(), any())).thenReturn(spec);
        when(spec.fetch()).thenReturn((FetchSpec) fetch);
        when(spec.map(any(java.util.function.BiFunction.class))).thenReturn((FetchSpec) fetch);
        when(spec.map(any(java.util.function.Function.class))).thenReturn((FetchSpec) fetch);
        when(fetch.one()).thenReturn(Mono.empty());
        when(fetch.all()).thenReturn(Flux.empty());
    }

    /**
     * Route the payee-boundary query (SQL containing {@code period_end_at})
     * to a dedicated FetchSpec that returns {@code boundary}; every other
     * SQL still returns empty. Keeps the ledger-loading paths quiet so
     * assertions can focus on the window boundary.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubDbWithBoundary(Instant boundary) {
        var boundarySpec = org.mockito.Mockito.mock(DatabaseClient.GenericExecuteSpec.class);
        var boundaryFetch = org.mockito.Mockito.mock(FetchSpec.class);
        when(boundarySpec.bind(anyString(), any())).thenReturn(boundarySpec);
        when(boundarySpec.map(any(java.util.function.BiFunction.class))).thenReturn(boundaryFetch);
        when(boundaryFetch.one()).thenReturn(Mono.just(boundary));

        when(db.sql(anyString())).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            return sql != null && sql.contains("period_end_at") ? boundarySpec : spec;
        });
        when(spec.bind(anyString(), any())).thenReturn(spec);
        when(spec.fetch()).thenReturn((FetchSpec) fetch);
        when(spec.map(any(java.util.function.BiFunction.class))).thenReturn((FetchSpec) fetch);
        when(spec.map(any(java.util.function.Function.class))).thenReturn((FetchSpec) fetch);
        when(fetch.one()).thenReturn(Mono.empty());
        when(fetch.all()).thenReturn(Flux.empty());
    }

    @SuppressWarnings({"unchecked"})
    private void stubAdviceSaveAndPublish() {
        when(adviceRepository.save(any())).thenAnswer(inv -> {
            PaymentAdviceRecord r = inv.getArgument(0);
            if (r.getId() == null) r.setId(UUID.randomUUID());
            return Mono.just(r);
        });
        when(adviceLineRepository.saveAll(any(Iterable.class))).thenReturn(Flux.empty());
        when(adviceRepository.findByPaymentRunIdAndProviderId(any(), any()))
            .thenAnswer(inv -> {
                var r = new PaymentAdviceRecord();
                r.setId(UUID.randomUUID());
                r.setAdviceNumber("ADV-000001");
                r.setPaymentRunId(inv.getArgument(0));
                r.setProviderId(inv.getArgument(1));
                r.setPayeeType("PROVIDER");
                r.setNetDueAmount(BigDecimal.ZERO);
                return Mono.just(r);
            });
        when(eventPublisher.publishAdviceGenerated(any(), any())).thenReturn(Mono.empty());
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());
    }

    private PaymentRun mkRun(UUID id, String currency) {
        var run = new PaymentRun();
        run.setId(id);
        run.setRunNumber("RUN-111111");
        run.setCurrencyCode(currency);
        run.setStatus("executed");
        run.setCreatedAt(Instant.now().minusSeconds(3600));
        run.setExecutedAt(Instant.now());
        return run;
    }

    private List<PaymentAdviceLine> capturedLines(ArgumentCaptor<Iterable<PaymentAdviceLine>> cap) {
        var iter = cap.getValue();
        var list = new java.util.ArrayList<PaymentAdviceLine>();
        iter.forEach(list::add);
        return list;
    }
}
