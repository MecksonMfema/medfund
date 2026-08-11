package com.medfund.contributions.service;

import com.medfund.contributions.dto.PageResponse;
import com.medfund.contributions.dto.ReceiptsAggregateRow;
import com.medfund.contributions.dto.ReceiptsDetailResponse;
import com.medfund.contributions.dto.ReceiptsSummaryRow;
import com.medfund.contributions.repository.ReceiptsReportQueryRepository;
import com.medfund.shared.report.MonthlyAggregateRow;
import com.medfund.shared.report.PerCurrencyTotal;
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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ReceiptsReportService} — verifies the service is
 * a thin façade that delegates to the query repository without in-memory
 * aggregation, and that dimension mapping catches typos loudly rather
 * than silently querying the wrong column.
 */
@ExtendWith(MockitoExtension.class)
class ReceiptsReportServiceTest {

    @Mock private ReceiptsReportQueryRepository repository;
    @InjectMocks private ReceiptsReportService service;

    private final LocalDate periodStart = LocalDate.of(2026, 7, 1);
    private final LocalDate periodEnd   = LocalDate.of(2026, 7, 31);

    @Test
    void perSchemeSummary_delegatesToRepository() {
        ReceiptsSummaryRow row = new ReceiptsSummaryRow(
                UUID.randomUUID(), "Gold", null, "USD", new BigDecimal("100.00"), 3L);
        when(repository.perSchemeSummary(periodStart, periodEnd)).thenReturn(Flux.just(row));

        StepVerifier.create(service.perSchemeSummary(periodStart, periodEnd))
                .expectNext(List.of(row))
                .verifyComplete();
    }

    @Test
    void perSchemePerCurrencyTotals_delegatesToRepository() {
        Map<String, PerCurrencyTotal> totals = Map.of("USD",
                new PerCurrencyTotal(new BigDecimal("100.00"), 3L));
        when(repository.perSchemePerCurrencyTotals(periodStart, periodEnd))
                .thenReturn(Mono.just(totals));

        StepVerifier.create(service.perSchemePerCurrencyTotals(periodStart, periodEnd))
                .expectNext(totals)
                .verifyComplete();
    }

    @Test
    void perGroupSummary_delegatesToRepository() {
        ReceiptsSummaryRow row = new ReceiptsSummaryRow(
                UUID.randomUUID(), "Acme", null, "USD", new BigDecimal("500.00"), 10L);
        when(repository.perGroupSummary(periodStart, periodEnd)).thenReturn(Flux.just(row));

        StepVerifier.create(service.perGroupSummary(periodStart, periodEnd))
                .expectNext(List.of(row))
                .verifyComplete();
    }

    @Test
    void perMemberSummary_stitchesContentAndCountIntoPage() {
        ReceiptsSummaryRow row = new ReceiptsSummaryRow(
                UUID.randomUUID(), "M-001 — Alice", "LIFE", "USD",
                new BigDecimal("50.00"), 2L);
        when(repository.perMemberSummary(eq(periodStart), eq(periodEnd),
                any(), any(), any(), eq(0), eq(50)))
                .thenReturn(Flux.just(row));
        when(repository.perMemberSummaryCount(eq(periodStart), eq(periodEnd),
                any(), any(), any()))
                .thenReturn(Mono.just(1L));

        StepVerifier.create(service.perMemberSummary(periodStart, periodEnd,
                        "Alice", "LIFE", null, 0, 50))
                .assertNext(page -> {
                    assertThat(page.total()).isEqualTo(1L);
                    assertThat(page.content()).containsExactly(row);
                    assertThat(page.page()).isZero();
                    assertThat(page.size()).isEqualTo(50);
                })
                .verifyComplete();
    }

    @Test
    void detail_composesMonthlyPlusLedgerIntoResponse() {
        UUID schemeId = UUID.randomUUID();
        ReceiptsDetailResponse.MonthlyBucket bucket = new ReceiptsDetailResponse.MonthlyBucket(
                periodStart, "USD", new BigDecimal("100.00"), 1L);
        ReceiptsDetailResponse.TransactionLedgerRow row = new ReceiptsDetailResponse.TransactionLedgerRow(
                UUID.randomUUID(), "T-001", Instant.parse("2026-07-05T10:00:00Z"),
                "PAYMENT", "BANK_TRANSFER", "REF1",
                new BigDecimal("100.00"), "USD");
        when(repository.monthlyBuckets(eq("r.attributed_scheme_id"), eq(schemeId), any(), any()))
                .thenReturn(Flux.just(bucket));
        when(repository.ledger(eq("r.attributed_scheme_id"), eq(schemeId), any(), any(),
                any(), any(), eq(0), eq(50)))
                .thenReturn(Flux.just(row));
        when(repository.ledgerCount(eq("r.attributed_scheme_id"), eq(schemeId), any(), any(),
                any(), any()))
                .thenReturn(Mono.just(1L));

        StepVerifier.create(service.detail("SCHEME", schemeId, periodStart, periodEnd,
                        null, null, 0, 50))
                .assertNext(detail -> {
                    assertThat(detail.dimensionId()).isEqualTo(schemeId);
                    assertThat(detail.monthlyBuckets()).containsExactly(bucket);
                    assertThat(detail.transactions().content()).containsExactly(row);
                    assertThat(detail.transactions().total()).isEqualTo(1L);
                })
                .verifyComplete();
    }

    @Test
    void unallocatedDetail_composesMonthlyPlusLedgerWithNullDimensionId() {
        ReceiptsDetailResponse.MonthlyBucket bucket = new ReceiptsDetailResponse.MonthlyBucket(
                periodStart, "USD", new BigDecimal("42.00"), 1L);
        ReceiptsDetailResponse.TransactionLedgerRow row = new ReceiptsDetailResponse.TransactionLedgerRow(
                UUID.randomUUID(), "T-002", Instant.parse("2026-07-06T10:00:00Z"),
                "PAYMENT", "CASH", null,
                new BigDecimal("42.00"), "USD");
        when(repository.monthlyBucketsUnallocated(periodStart, periodEnd))
                .thenReturn(Flux.just(bucket));
        when(repository.ledger(eq("r.attributed_scheme_id"), eq(null), any(), any(),
                any(), any(), eq(0), eq(50)))
                .thenReturn(Flux.just(row));
        when(repository.ledgerCount(eq("r.attributed_scheme_id"), eq(null), any(), any(),
                any(), any()))
                .thenReturn(Mono.just(1L));

        StepVerifier.create(service.unallocatedDetail(periodStart, periodEnd, null, null, 0, 50))
                .assertNext(detail -> {
                    assertThat(detail.dimensionId()).isNull();
                    assertThat(detail.dimensionName()).isEqualTo("Unallocated group payments");
                    assertThat(detail.monthlyBuckets()).containsExactly(bucket);
                    assertThat(detail.transactions().content()).containsExactly(row);
                })
                .verifyComplete();
    }

    @Test
    void aggregatePerScheme_delegatesToRepository() {
        ReceiptsAggregateRow row = new ReceiptsAggregateRow(
                "SCHEME", UUID.randomUUID(), "Gold", "USD", new BigDecimal("100.00"));
        when(repository.aggregatePerScheme(periodStart, periodEnd)).thenReturn(Flux.just(row));

        StepVerifier.create(service.aggregatePerScheme(periodStart, periodEnd))
                .expectNext(List.of(row))
                .verifyComplete();
    }

    @Test
    void aggregateMonthly_delegatesToRepository() {
        MonthlyAggregateRow row = new MonthlyAggregateRow(
                "SCHEME", UUID.randomUUID(), "Gold", "USD",
                periodStart, new BigDecimal("100.00"));
        when(repository.aggregateMonthly("SCHEME", periodStart, periodEnd))
                .thenReturn(Flux.just(row));

        StepVerifier.create(service.aggregateMonthly("SCHEME", periodStart, periodEnd))
                .expectNext(List.of(row))
                .verifyComplete();
    }

    @Test
    void dimensionColumn_rejectsUnknownDimensionLoudly() {
        // A silent fallback would let a bad ?dimension= query the wrong SQL column and
        // silently return the wrong data. The service must fail loud.
        assertThatThrownBy(() -> ReceiptsReportService.dimensionColumn("PROVIDER"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dimension");
    }

    @Test
    void dimensionColumn_mapsThreeCanonicalDimensions() {
        assertThat(ReceiptsReportService.dimensionColumn("SCHEME"))
                .isEqualTo("r.attributed_scheme_id");
        assertThat(ReceiptsReportService.dimensionColumn("group")).isEqualTo("r.group_id");
        assertThat(ReceiptsReportService.dimensionColumn("Member")).isEqualTo("r.member_id");
    }
}
