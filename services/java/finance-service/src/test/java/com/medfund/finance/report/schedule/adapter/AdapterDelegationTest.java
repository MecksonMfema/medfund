package com.medfund.finance.report.schedule.adapter;

import com.medfund.finance.kpi.dto.KpiRequest;
import com.medfund.finance.kpi.service.KpiWorkbookService;
import com.medfund.finance.producer.service.CommissionWorkbookService;
import com.medfund.finance.reinsurance.service.BordereauReportWorkbookService;
import com.medfund.finance.report.schedule.ScheduledFireContext;
import com.medfund.finance.report.schedule.ScheduledReportShapeAdapter;
import com.medfund.finance.service.CollectionRateExcelService;
import com.medfund.finance.service.LossRatioExcelService;
import com.medfund.shared.report.ReportKey;
import com.medfund.shared.report.ReportPeriodShape;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The 5 finance-local adapters are one-line delegations; the tests here verify
 * (a) the {@link ReportKey} + {@link ReportPeriodShape} pair, (b) that the
 * shape service is called with the correct arguments derived from the
 * {@link ScheduledFireContext}. Rendering itself is exercised by each shape
 * service's own test suite.
 */
@ExtendWith(MockitoExtension.class)
class AdapterDelegationTest {

    @Mock private CommissionWorkbookService commissionService;
    @Mock private LossRatioExcelService lossRatioService;
    @Mock private CollectionRateExcelService collectionRateService;
    @Mock private BordereauReportWorkbookService bordereauService;
    @Mock private KpiWorkbookService kpiWorkbookService;

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID SCHEDULE = UUID.randomUUID();
    private static final LocalDate PS = LocalDate.of(2026, 8, 1);
    private static final LocalDate PE = LocalDate.of(2026, 8, 31);
    private static final OffsetDateTime FIRED = OffsetDateTime.parse("2026-09-01T08:05:00Z");

    private ScheduledFireContext ctx(ReportKey key) {
        return new ScheduledFireContext(
                TENANT, key, SCHEDULE, PS, PE, PE, "USD", "Monthly",
                FIRED, UUID.randomUUID(), "admin@acme");
    }

    @Test
    void commissionAdapter_delegatesWithNullProducerAndTenantCurrency() {
        when(commissionService.statementWorkbook(any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(new byte[]{1}));
        var adapter = new CommissionStatementAdapter(commissionService);

        assertThat(adapter.key()).isEqualTo(ReportKey.COMMISSION_STATEMENT);
        assertThat(adapter.periodShape()).isEqualTo(ReportPeriodShape.PREVIOUS_COMPLETE_PERIOD);

        StepVerifier.create(adapter.render(ctx(ReportKey.COMMISSION_STATEMENT)))
                .expectNextCount(1)
                .verifyComplete();

        verify(commissionService).statementWorkbook(eq(PS), eq(PE), isNull(), eq("USD"), eq(TENANT));
    }

    @Test
    void lossRatioAdapter_passesPeriodAndFreshWarningsList() {
        when(lossRatioService.workbook(any(), any(), any()))
                .thenReturn(Mono.just(new byte[]{1}));
        var adapter = new LossRatioAdapter(lossRatioService);

        assertThat(adapter.key()).isEqualTo(ReportKey.LOSS_RATIO);
        assertThat(adapter.periodShape()).isEqualTo(ReportPeriodShape.PREVIOUS_COMPLETE_PERIOD);

        StepVerifier.create(adapter.render(ctx(ReportKey.LOSS_RATIO)))
                .expectNextCount(1)
                .verifyComplete();

        verify(lossRatioService).workbook(eq(PS), eq(PE), any(List.class));
    }

    @Test
    void collectionRateAdapter_passesPeriodAndFreshWarningsList() {
        when(collectionRateService.workbook(any(), any(), any()))
                .thenReturn(Mono.just(new byte[]{1}));
        var adapter = new CollectionRateAdapter(collectionRateService);

        assertThat(adapter.key()).isEqualTo(ReportKey.COLLECTION_RATE);
        assertThat(adapter.periodShape()).isEqualTo(ReportPeriodShape.PREVIOUS_COMPLETE_PERIOD);

        StepVerifier.create(adapter.render(ctx(ReportKey.COLLECTION_RATE)))
                .expectNextCount(1)
                .verifyComplete();

        verify(collectionRateService).workbook(eq(PS), eq(PE), any(List.class));
    }

    @Test
    void cessionBordereauAdapter_derivesQuarterFromPeriodStart() {
        when(bordereauService.cessionWorkbook(any(), any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(), any(), any()))
                .thenReturn(Mono.just(new byte[]{1}));
        var adapter = new ReinsuranceCessionBordereauAdapter(bordereauService);

        assertThat(adapter.key()).isEqualTo(ReportKey.REINSURANCE_CESSION_BORDEREAU);
        StepVerifier.create(adapter.render(ctx(ReportKey.REINSURANCE_CESSION_BORDEREAU)))
                .expectNextCount(1)
                .verifyComplete();

        // periodStart = 2026-08-01 → Q3.
        verify(bordereauService).cessionWorkbook(isNull(), isNull(), eq(2026), eq(3),
                eq("USD"), eq(TENANT));
    }

    @Test
    void recoveriesAdapter_derivesQuarterFromPeriodStart() {
        when(bordereauService.recoveriesWorkbook(any(), any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(), any(), any()))
                .thenReturn(Mono.just(new byte[]{1}));
        var adapter = new ReinsuranceRecoveriesAdapter(bordereauService);

        assertThat(adapter.key()).isEqualTo(ReportKey.REINSURANCE_RECOVERIES);
        StepVerifier.create(adapter.render(ctx(ReportKey.REINSURANCE_RECOVERIES)))
                .expectNextCount(1)
                .verifyComplete();

        verify(bordereauService).recoveriesWorkbook(isNull(), isNull(), eq(2026), eq(3),
                eq("USD"), eq(TENANT));
    }

    // ── Phase 18 §Phase 8 — KPI adapters ────────────────────────────────

    /** Every KPI adapter is a one-line delegation: same context → same
     *  {@link KpiRequest} with schedule-scope filter chips (null). */
    private void assertKpiAdapter(ScheduledReportShapeAdapter adapter,
                                  ReportKey expectedKey) {
        when(kpiWorkbookService.workbook(eq(expectedKey), any(KpiRequest.class)))
                .thenReturn(Mono.just(new byte[]{1}));

        assertThat(adapter.key()).isEqualTo(expectedKey);
        assertThat(adapter.periodShape()).isEqualTo(ReportPeriodShape.PREVIOUS_COMPLETE_PERIOD);

        StepVerifier.create(adapter.render(ctx(expectedKey)))
                .expectNextCount(1)
                .verifyComplete();

        ArgumentCaptor<KpiRequest> captor = ArgumentCaptor.forClass(KpiRequest.class);
        verify(kpiWorkbookService).workbook(eq(expectedKey), captor.capture());
        KpiRequest req = captor.getValue();
        assertThat(req.tenantId()).isEqualTo(TENANT);
        assertThat(req.periodStart()).isEqualTo(PS);
        assertThat(req.periodEnd()).isEqualTo(PE);
        assertThat(req.reportingCurrency()).isEqualTo("USD");
        // K13: filter chips are never populated on scheduled fires — the
        // schedule scope is tenant-wide.
        assertThat(req.insuranceLine()).isNull();
        assertThat(req.schemeId()).isNull();
        assertThat(req.producerId()).isNull();
    }

    @Test
    void lossRatioKpiAdapter_delegatesToWorkbookServiceWithTenantWideSlice() {
        assertKpiAdapter(new LossRatioKpiAdapter(kpiWorkbookService), ReportKey.LOSS_RATIO_KPI);
    }

    @Test
    void expenseRatioAdapter_delegatesToWorkbookServiceWithTenantWideSlice() {
        assertKpiAdapter(new ExpenseRatioAdapter(kpiWorkbookService), ReportKey.EXPENSE_RATIO);
    }

    @Test
    void combinedRatioAdapter_delegatesToWorkbookServiceWithTenantWideSlice() {
        assertKpiAdapter(new CombinedRatioAdapter(kpiWorkbookService), ReportKey.COMBINED_RATIO);
    }

    @Test
    void claimsFrequencyAdapter_delegatesToWorkbookServiceWithTenantWideSlice() {
        assertKpiAdapter(new ClaimsFrequencyAdapter(kpiWorkbookService), ReportKey.CLAIMS_FREQUENCY);
    }

    @Test
    void averageSeverityAdapter_delegatesToWorkbookServiceWithTenantWideSlice() {
        assertKpiAdapter(new AverageSeverityAdapter(kpiWorkbookService), ReportKey.AVERAGE_SEVERITY);
    }

    @Test
    void quarterOf_boundaries() {
        assertThat(ReinsuranceCessionBordereauAdapter.quarterOf(LocalDate.of(2026, 1, 1))).isEqualTo(1);
        assertThat(ReinsuranceCessionBordereauAdapter.quarterOf(LocalDate.of(2026, 3, 31))).isEqualTo(1);
        assertThat(ReinsuranceCessionBordereauAdapter.quarterOf(LocalDate.of(2026, 4, 1))).isEqualTo(2);
        assertThat(ReinsuranceCessionBordereauAdapter.quarterOf(LocalDate.of(2026, 12, 31))).isEqualTo(4);
    }
}
