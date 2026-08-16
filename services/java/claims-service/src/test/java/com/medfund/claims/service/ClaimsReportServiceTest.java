package com.medfund.claims.service;

import com.medfund.claims.dto.ClaimStatusMatrixCell;
import com.medfund.claims.dto.ClaimStatusMatrixResponse;
import com.medfund.claims.dto.ClaimsAggregateRow;
import com.medfund.claims.dto.ClaimsDetailResponse;
import com.medfund.claims.dto.ClaimsSummaryRow;
import com.medfund.claims.dto.DenialAnalysisResponse;
import com.medfund.claims.dto.FrequencySeverityRow;
import com.medfund.claims.dto.PageResponse;
import com.medfund.claims.repository.ClaimsReportQueryRepository;
import com.medfund.shared.report.MonthlyAggregateRow;
import com.medfund.shared.report.PerCurrencyTotal;
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
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ClaimsReportService} (Phase 4 §A) — a thin façade
 * over {@link ClaimsReportQueryRepository}. Pins the dimension→column
 * mapping, the detail page composition, and the pass-through of every
 * summary / aggregate read. Every {@code Mono}-returning repository call is
 * stubbed in each test (Mockito 5 — never rely on defaults for a return
 * used inside a {@code zipWith}).
 */
@ExtendWith(MockitoExtension.class)
class ClaimsReportServiceTest {

    @Mock private ClaimsReportQueryRepository repository;

    @InjectMocks private ClaimsReportService service;

    private static final LocalDate START = LocalDate.of(2026, 7, 1);
    private static final LocalDate END = LocalDate.of(2026, 7, 31);

    @BeforeEach
    void setUp() {
        // no-op — stubs are per-test so the mapping is explicit
    }

    @Test
    void perSchemeSummary_forwardsToRepository() {
        ClaimsSummaryRow row = summaryRow("Gold", "USD", 10);
        when(repository.perSchemeSummary(START, END, "HEALTH")).thenReturn(Flux.just(row));
        when(repository.perSchemeSummary(START, END, null)).thenReturn(Flux.empty());

        StepVerifier.create(service.perSchemeSummary(START, END, "HEALTH"))
                .expectNext(List.of(row))
                .verifyComplete();

        StepVerifier.create(service.perSchemeSummary(START, END, null))
                .expectNext(List.of())
                .verifyComplete();
    }

    @Test
    void perProviderSummary_forwardsToRepository() {
        ClaimsSummaryRow row = summaryRow("Clinic A", "ZAR", 5);
        when(repository.perProviderSummary(START, END, null)).thenReturn(Flux.just(row));

        StepVerifier.create(service.perProviderSummary(START, END, null))
                .expectNext(List.of(row))
                .verifyComplete();
    }

    @Test
    void claimsPerCurrencyTotals_forwardsToRepository() {
        PerCurrencyTotal total = new PerCurrencyTotal(new BigDecimal("100.00"), 3L);
        when(repository.claimsPerCurrencyTotals(START, END, null)).thenReturn(Mono.just(Map.of("USD", total)));

        StepVerifier.create(service.claimsPerCurrencyTotals(START, END, null))
                .expectNextMatches(map -> map.get("USD").rowCount() == 3L)
                .verifyComplete();
    }

    @Test
    void detail_composesBucketsWithPagedLedger() {
        UUID schemeId = UUID.randomUUID();
        ClaimsDetailResponse.MonthlyBucket bucket = new ClaimsDetailResponse.MonthlyBucket(
                LocalDate.of(2026, 7, 1), "USD", 2L,
                new BigDecimal("200.00"), new BigDecimal("180.00"), new BigDecimal("160.00"));
        ClaimsDetailResponse.ClaimLedgerRow row = ledgerRow("CLM-1");

        when(repository.monthlyBuckets("c.scheme_id", schemeId, START, END)).thenReturn(Flux.just(bucket));
        when(repository.ledger("c.scheme_id", schemeId, START, END, null, null, null, 0, 50))
                .thenReturn(Flux.just(row));
        when(repository.ledgerCount("c.scheme_id", schemeId, START, END, null, null, null))
                .thenReturn(Mono.just(2L));
        when(repository.dimensionName("SCHEME", schemeId)).thenReturn(Mono.just("Gold"));

        StepVerifier.create(service.detail("SCHEME", schemeId, START, END, null, null, null, 0, 50))
                .assertNext(detail -> {
                    assertThat(detail.dimensionId()).isEqualTo(schemeId);
                    assertThat(detail.dimensionName()).isEqualTo("Gold");
                    assertThat(detail.monthlyBuckets()).hasSize(1);
                    assertThat(detail.claims().content()).containsExactly(row);
                    assertThat(detail.claims().total()).isEqualTo(2L);
                    assertThat(detail.claims().page()).isZero();
                    assertThat(detail.claims().size()).isEqualTo(50);
                })
                .verifyComplete();
    }

    @Test
    void detail_passesThroughStatusAndProviderFilters() {
        UUID providerId = UUID.randomUUID();
        when(repository.monthlyBuckets(any(), any(), any(), any())).thenReturn(Flux.empty());
        when(repository.ledger(eq("c.provider_id"), any(), any(), any(),
                eq("PAID"), eq(providerId), isNull(), anyInt(), anyInt()))
                .thenReturn(Flux.empty());
        when(repository.ledgerCount(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(0L));
        when(repository.dimensionName(eq("PROVIDER"), any())).thenReturn(Mono.empty());

        StepVerifier.create(service.detail("PROVIDER", UUID.randomUUID(), START, END,
                        "PAID", providerId, null, 2, 25))
                .assertNext(detail -> {
                    assertThat(detail.claims().page()).isEqualTo(2);
                    assertThat(detail.dimensionName()).isEmpty();
                })
                .verifyComplete();
    }

    // ── §B per-group / per-member ─────────────────────────────────────────

    @Test
    void perGroupSummary_forwardsToRepository() {
        ClaimsSummaryRow row = summaryRow("Acme Corp", "USD", 4);
        when(repository.perGroupSummary(START, END, null)).thenReturn(Flux.just(row));

        StepVerifier.create(service.perGroupSummary(START, END, null))
                .expectNext(List.of(row))
                .verifyComplete();
    }

    @Test
    void perMemberSummary_composesPagedResultFromRepository() {
        UUID schemeId = UUID.randomUUID();
        ClaimsSummaryRow row = summaryRow("Alice A.", "USD", 2);
        when(repository.perMemberSummary(START, END, "ali", "HEALTH", schemeId, null, 0, 50))
                .thenReturn(Flux.just(row));
        when(repository.perMemberCount(START, END, "ali", "HEALTH", schemeId, null))
                .thenReturn(Mono.just(7L));

        StepVerifier.create(service.perMemberSummary(START, END, "ali", "HEALTH", schemeId, null, 0, 50))
                .assertNext(page -> {
                    assertThat(page.content()).containsExactly(row);
                    assertThat(page.total()).isEqualTo(7L);
                    assertThat(page.totalPages()).isEqualTo(1);
                })
                .verifyComplete();
    }

    @Test
    void memberPerCurrencyTotals_forwardsToRepository() {
        PerCurrencyTotal total = new PerCurrencyTotal(new BigDecimal("50.00"), 2L);
        when(repository.memberPerCurrencyTotals(START, END, null, null, null, null))
                .thenReturn(Mono.just(Map.of("USD", total)));

        StepVerifier.create(service.memberPerCurrencyTotals(START, END, null, null, null, null))
                .expectNextMatches(map -> map.get("USD").rowCount() == 2L)
                .verifyComplete();
    }

    // ── §B CLAIM_STATUS_LIST ──────────────────────────────────────────────

    @Test
    void statusMatrix_collectsCellsWithAsOfInstant() {
        when(repository.statusMatrixCells(START, END, null))
                .thenReturn(Flux.just(statusCell("REJECTED", "8-14")));

        StepVerifier.create(service.statusMatrix(START, END, null))
                .assertNext(m -> {
                    assertThat(m.submittedFrom()).isEqualTo(START);
                    assertThat(m.submittedTo()).isEqualTo(END);
                    assertThat(m.cells()).hasSize(1);
                    assertThat(m.cells().get(0).status()).isEqualTo("REJECTED");
                    assertThat(m.asOf()).isNotNull();
                })
                .verifyComplete();
    }

    @Test
    void statusMatrixDrill_composesPagedLedger() {
        ClaimsDetailResponse.ClaimLedgerRow row = ledgerRow("CLM-9");
        when(repository.statusMatrixLedger(START, END, "REJECTED", ">30", 0, 25))
                .thenReturn(Flux.just(row));
        when(repository.statusMatrixLedgerCount(START, END, "REJECTED", ">30"))
                .thenReturn(Mono.just(1L));

        StepVerifier.create(service.statusMatrixDrill(START, END, "REJECTED", ">30", 0, 25))
                .assertNext(page -> {
                    assertThat(page.content()).containsExactly(row);
                    assertThat(page.total()).isEqualTo(1L);
                })
                .verifyComplete();
    }

    // ── §B DENIAL_ANALYSIS ─────────────────────────────────────────────────

    @Test
    void denialAnalysis_composesFourViews() {
        LocalDate jun1 = LocalDate.of(2026, 6, 1);
        LocalDate jul31 = LocalDate.of(2026, 7, 31);
        DenialAnalysisResponse.CategoryRow cat = new DenialAnalysisResponse.CategoryRow("ELIGIBILITY", 3L, new BigDecimal("30.00"));
        DenialAnalysisResponse.CodeRow codeRow = new DenialAnalysisResponse.CodeRow("R01", "ELIGIBILITY", "desc", 2L, new BigDecimal("20.00"));
        DenialAnalysisResponse.ProviderRow prov = new DenialAnalysisResponse.ProviderRow(UUID.randomUUID(), "Clinic A", 1L, new BigDecimal("10.00"), new BigDecimal("50.00"));
        DenialAnalysisResponse.MonthlyRow month = new DenialAnalysisResponse.MonthlyRow(LocalDate.of(2026, 6, 1), 1L, new BigDecimal("10.00"));

        when(repository.denialByCategory(jun1, jul31, null, null, null)).thenReturn(Flux.just(cat));
        when(repository.denialByCode(jun1, jul31, null, null, null)).thenReturn(Flux.just(codeRow));
        when(repository.denialByProvider(jun1, jul31, null, null, null)).thenReturn(Flux.just(prov));
        when(repository.denialMonthlyTrend(jun1, jul31, null, null, null)).thenReturn(Flux.just(month));

        StepVerifier.create(service.denialAnalysis(jun1, jul31, null, null, null))
                .assertNext(r -> {
                    assertThat(r.byCategory()).containsExactly(cat);
                    assertThat(r.byCode()).containsExactly(codeRow);
                    assertThat(r.byProvider()).containsExactly(prov);
                    assertThat(r.monthlyTrend()).containsExactly(month);
                })
                .verifyComplete();
    }

    @Test
    void denialAnalysis_singleMonthWindow_skipsMonthlyTrendQuery() {
        LocalDate singleStart = LocalDate.of(2026, 7, 1);
        LocalDate singleEnd   = LocalDate.of(2026, 7, 31);

        when(repository.denialByCategory(any(), any(), any(), any(), any())).thenReturn(Flux.empty());
        when(repository.denialByCode(any(), any(), any(), any(), any())).thenReturn(Flux.empty());
        when(repository.denialByProvider(any(), any(), any(), any(), any())).thenReturn(Flux.empty());

        StepVerifier.create(service.denialAnalysis(singleStart, singleEnd, null, null, null))
                .assertNext(r -> {
                    assertThat(r.monthlyTrend()).isEmpty();
                    assertThat(r.byCategory()).isEmpty();
                })
                .verifyComplete();

        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never())
                .denialMonthlyTrend(any(), any(), any(), any(), any());
    }

    // ── §B CLAIMS_FREQUENCY_SEVERITY ──────────────────────────────────────

    @Test
    void frequencySeverity_computesDaysAndCarriesExposureWarning() {
        FrequencySeverityRow row = new FrequencySeverityRow(
                UUID.randomUUID(), "Gold", "HEALTH", new BigDecimal("82.00"), 10L,
                new BigDecimal("1.46"), "USD",
                new BigDecimal("120.00"), new BigDecimal("90.00"), new BigDecimal("200.00"));
        when(repository.frequencySeverity(START, END, "HEALTH", 31L)).thenReturn(Flux.just(row));

        StepVerifier.create(service.frequencySeverity(START, END, "HEALTH"))
                .assertNext(result -> {
                    assertThat(result.rows()).containsExactly(row);
                    assertThat(result.exposureWarning()).contains("member_status_history");
                })
                .verifyComplete();
    }

    // ── Cross-service aggregates ───────────────────────────────────────────

    @Test
    void aggregate_forwardsDimensionToRepository() {
        ClaimsAggregateRow row = new ClaimsAggregateRow(
                "SCHEME", UUID.randomUUID(), "Gold", "USD",
                new BigDecimal("100.00"), new BigDecimal("90.00"), new BigDecimal("80.00"));
        when(repository.aggregate("SCHEME", START, END)).thenReturn(Flux.just(row));

        StepVerifier.create(service.aggregate("SCHEME", START, END))
                .expectNext(List.of(row))
                .verifyComplete();
    }

    @Test
    void aggregate_propagatesInvalidDimensionError() {
        when(repository.aggregate("GROUP", START, END)).thenReturn(Flux.error(
                new IllegalArgumentException("dimension must be SCHEME|GROUP|MEMBER|PROVIDER, got 'GROUP'")));

        StepVerifier.create(service.aggregate("GROUP", START, END))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    void aggregateMonthly_forwardsDimensionToRepository() {
        MonthlyAggregateRow row = new MonthlyAggregateRow(
                "SCHEME", UUID.randomUUID(), "Gold", "USD",
                LocalDate.of(2026, 7, 1), new BigDecimal("80.00"));
        when(repository.aggregateMonthly("SCHEME", START, END)).thenReturn(Flux.just(row));

        StepVerifier.create(service.aggregateMonthly("SCHEME", START, END))
                .expectNext(List.of(row))
                .verifyComplete();
    }

    @Test
    void dimensionColumn_mapsPublicDimensionToSqlColumn() {
        assertThat(ClaimsReportService.dimensionColumn("SCHEME")).isEqualTo("c.scheme_id");
        assertThat(ClaimsReportService.dimensionColumn("PROVIDER")).isEqualTo("c.provider_id");
        assertThat(ClaimsReportService.dimensionColumn("provider")).isEqualTo("c.provider_id");
        assertThat(ClaimsReportService.dimensionColumn("GROUP")).isEqualTo("m.group_id");
        assertThat(ClaimsReportService.dimensionColumn("MEMBER")).isEqualTo("c.member_id");
        assertThat(ClaimsReportService.dimensionColumn("member")).isEqualTo("c.member_id");
    }

    @Test
    void dimensionColumn_rejectsNullAndUnknownDimensions() {
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> ClaimsReportService.dimensionColumn(null));
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> ClaimsReportService.dimensionColumn("CAR"));
    }

    private static ClaimsSummaryRow summaryRow(String name, String ccy, int count) {
        return new ClaimsSummaryRow(
                UUID.randomUUID(), name, null, ccy, count,
                new BigDecimal("100.00"), new BigDecimal("90.00"), new BigDecimal("80.00"));
    }

    private static ClaimStatusMatrixCell statusCell(String status, String bucket) {
        return new ClaimStatusMatrixCell(
                status, bucket, 2L,
                new BigDecimal("200.00"), new BigDecimal("180.00"), new BigDecimal("160.00"), "USD");
    }

    private static ClaimsDetailResponse.ClaimLedgerRow ledgerRow(String claimNumber) {
        return new ClaimsDetailResponse.ClaimLedgerRow(
                UUID.randomUUID(), claimNumber, "Alice A.", "Clinic A",
                Instant.now(), LocalDate.of(2026, 7, 15), Instant.now(),
                "PAID", null,
                new BigDecimal("100.00"), new BigDecimal("90.00"), new BigDecimal("80.00"), "USD");
    }
}
