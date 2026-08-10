package com.medfund.finance.service;

import com.medfund.finance.entity.PaymentAdviceLine;
import com.medfund.finance.entity.PaymentAdviceRecord;
import com.medfund.finance.entity.PaymentRun;
import com.medfund.finance.entity.PaymentRunItem;
import com.medfund.finance.repository.PaymentAdviceLineRepository;
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
    void findFiltered_periodBounds_buildBoundedQuery() {
        var builtSql = new java.util.concurrent.atomic.AtomicReference<String>();
        when(db.sql(anyString())).thenAnswer(inv -> {
            builtSql.set(inv.getArgument(0));
            return spec;
        });
        when(spec.bind(anyString(), any())).thenReturn(spec);
        when(spec.map(any(java.util.function.BiFunction.class))).thenReturn((FetchSpec) fetch);
        when(spec.map(any(java.util.function.Function.class))).thenReturn((FetchSpec) fetch);
        when(fetch.all()).thenReturn(Flux.empty());

        Instant start = Instant.parse("2026-08-01T00:00:00Z");
        Instant end   = Instant.parse("2026-08-31T23:59:59Z");

        StepVerifier.create(service.findFiltered(null, null, null, start, end)
                .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant")))
            .verifyComplete();

        String sql = builtSql.get();
        assertThat(sql).contains("period_end_at >= :periodStart");
        assertThat(sql).contains("period_end_at <= :periodEnd");
        // No payee-side WHERE clauses when only period bounds are set.
        assertThat(sql).doesNotContain("payment_run_id = :runId");
        assertThat(sql).doesNotContain("provider_id    = :providerId");
        assertThat(sql).doesNotContain("member_id      = :memberId");
        // Actually bound the params (guards against silent binding drops).
        verify(spec).bind("periodStart", start);
        verify(spec).bind("periodEnd", end);
    }

    @Test
    void findFiltered_noFilters_selectsAll() {
        var builtSql = new java.util.concurrent.atomic.AtomicReference<String>();
        when(db.sql(anyString())).thenAnswer(inv -> {
            builtSql.set(inv.getArgument(0));
            return spec;
        });
        when(spec.map(any(java.util.function.BiFunction.class))).thenReturn((FetchSpec) fetch);
        when(spec.map(any(java.util.function.Function.class))).thenReturn((FetchSpec) fetch);
        when(fetch.all()).thenReturn(Flux.empty());

        StepVerifier.create(service.findFiltered(null, null, null, null, null)
                .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant")))
            .verifyComplete();

        String sql = builtSql.get();
        assertThat(sql).contains("SELECT * FROM payment_advices");
        assertThat(sql).contains("ORDER BY issued_at DESC");
        assertThat(sql).doesNotContain(":periodStart");
        assertThat(sql).doesNotContain(":providerId");
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
