package com.medfund.finance.kpi.service;

import com.medfund.finance.client.ClaimsClient;
import com.medfund.finance.client.ContributionsClient;
import com.medfund.finance.dto.BillingAggregateRow;
import com.medfund.finance.dto.ClaimsIncurredAggregateRow;
import com.medfund.finance.dto.PremiumEarnedAggregateRow;
import com.medfund.finance.kpi.dto.KpiDashboardResponse;
import com.medfund.finance.kpi.dto.KpiReportData;
import com.medfund.finance.kpi.dto.KpiRequest;
import com.medfund.finance.kpi.dto.KpiTrendPoint;
import com.medfund.finance.producer.dto.CommissionAggregateRow;
import com.medfund.finance.producer.repository.CommissionAggregateQueryRepository.AggregateDimension;
import com.medfund.finance.producer.service.CommissionAggregateService;
import com.medfund.shared.report.FxRateReader;
import com.medfund.shared.report.ReportEnablementReader;
import com.medfund.shared.report.ReportKey;
import com.medfund.shared.report.ReportResponse;
import com.medfund.shared.report.ReportingCurrencyResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit-level composer tests — Mockito over the four peer clients + IBNR
 * lookup + FX reader + currency resolver + Redis. Covers every load-bearing
 * arithmetic path: per-currency native ratios, composite in reporting
 * currency, IBNR addition, small-denominator warning, cache hit/miss, and
 * batch-endpoint gate cascade.
 */
class KpiComposerServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final LocalDate PERIOD_START = LocalDate.of(2026, 7, 1);
    private static final LocalDate PERIOD_END   = LocalDate.of(2026, 8, 1);
    private static final KpiRequest REQ = new KpiRequest(TENANT_ID,
            PERIOD_START, PERIOD_END, "USD", null, null, null);

    private ContributionsClient contributionsClient;
    private ClaimsClient claimsClient;
    private CommissionAggregateService commissionAggregateService;
    private IbnrLookupService ibnrLookupService;
    private FxRateReader fxRateReader;
    private ReportingCurrencyResolver currencyResolver;
    private ReportEnablementReader reportEnablementReader;
    private ReactiveRedisTemplate<String, KpiReportData> kpiCache;
    private ReactiveValueOperations<String, KpiReportData> valueOps;

    private KpiComposerService composer;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        contributionsClient = mock(ContributionsClient.class);
        claimsClient = mock(ClaimsClient.class);
        commissionAggregateService = mock(CommissionAggregateService.class);
        ibnrLookupService = mock(IbnrLookupService.class);
        fxRateReader = mock(FxRateReader.class);
        currencyResolver = mock(ReportingCurrencyResolver.class);
        reportEnablementReader = mock(ReportEnablementReader.class);
        kpiCache = mock(ReactiveRedisTemplate.class);
        valueOps = mock(ReactiveValueOperations.class);

        when(kpiCache.opsForValue()).thenReturn(valueOps);
        // Default: cache empty; every set writes and returns TRUE.
        when(valueOps.get(anyString())).thenReturn(Mono.empty());
        when(valueOps.set(anyString(), any(), any(Duration.class))).thenReturn(Mono.just(true));
        when(currencyResolver.resolve(any(), any())).thenReturn(Mono.just("USD"));
        when(reportEnablementReader.isEnabled(any(), any())).thenReturn(Mono.just(true));
        // Identity FX by default; per-test overrides for multi-currency paths.
        when(fxRateReader.convert(any(), anyString(), anyString(), any(), any()))
                .thenAnswer(inv -> Mono.just((BigDecimal) inv.getArgument(0)));
        when(ibnrLookupService.latestIbnrTotal(any(), any(), any(), anyList()))
                .thenReturn(Mono.just(Optional.<BigDecimal>empty()));

        composer = new KpiComposerService(contributionsClient, claimsClient,
                commissionAggregateService, ibnrLookupService, fxRateReader,
                currencyResolver, reportEnablementReader, kpiCache);
    }

    @Test
    void lossRatio_singleCurrencyHappyPath_computesCompositeAndPerCurrency() {
        when(claimsClient.claimsIncurred(any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(List.of(claimsRow("USD", "HEALTH",
                        new BigDecimal("70"), new BigDecimal("10"), 5))));
        when(contributionsClient.earnedPremium(any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(List.of(earnedRow("USD", "HEALTH",
                        new BigDecimal("100"), 12))));

        StepVerifier.create(composer.lossRatio(REQ))
                .assertNext(env -> {
                    assertThat(env.reportKey()).isEqualTo(ReportKey.LOSS_RATIO_KPI.name());
                    // Composite = (subtotalIncurredExIbnr + IBNR=0) / earnedPremium = 70/100
                    assertThat(env.data().compositeRatio()).isEqualByComparingTo("0.700000");
                    assertThat(env.data().compositeNumerator()).isEqualByComparingTo("70");
                    assertThat(env.data().compositeDenominator()).isEqualByComparingTo("100");
                    assertThat(env.data().perCurrency()).containsKey("USD");
                    assertThat(env.data().perCurrency().get("USD").ratio()).isEqualByComparingTo("0.700000");
                })
                .verifyComplete();
    }

    @Test
    void lossRatio_missingIbnr_warningPopulatedAndZeroIbnrInComposite() {
        when(claimsClient.claimsIncurred(any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(List.of(claimsRow("USD", "HEALTH",
                        new BigDecimal("80"), new BigDecimal("10"), 3))));
        when(contributionsClient.earnedPremium(any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(List.of(earnedRow("USD", "HEALTH",
                        new BigDecimal("100"), 12))));
        // The default IBNR stub returns empty AND appends warning to the list — mirror that here explicitly
        when(ibnrLookupService.latestIbnrTotal(any(), any(), any(), anyList()))
                .thenAnswer(inv -> {
                    List<String> warnings = inv.getArgument(3);
                    warnings.add("IBNR run pending or older than 90 days for (line=all lines, asOf="
                            + PERIOD_END + ") — displaying paid + Δreserve only");
                    return Mono.just(Optional.<BigDecimal>empty());
                });

        StepVerifier.create(composer.lossRatio(REQ))
                .assertNext(env -> {
                    assertThat(env.warnings()).anyMatch(w -> w.contains("IBNR run pending"));
                    // composite = 80/100 (no IBNR)
                    assertThat(env.data().compositeRatio()).isEqualByComparingTo("0.800000");
                })
                .verifyComplete();
    }

    @Test
    void lossRatio_multiCurrency_convertsCompositeViaFxRateReader() {
        when(claimsClient.claimsIncurred(any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(List.of(
                        claimsRow("USD", "HEALTH", new BigDecimal("60"), BigDecimal.ZERO, 4),
                        claimsRow("ZWL", "HEALTH", new BigDecimal("3200"), BigDecimal.ZERO, 2))));
        when(contributionsClient.earnedPremium(any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(List.of(
                        earnedRow("USD", "HEALTH", new BigDecimal("100"), 12),
                        earnedRow("ZWL", "HEALTH", new BigDecimal("5000"), 12))));
        // ZWL → USD at 0.02
        when(fxRateReader.convert(any(), eq("USD"), eq("USD"), any(), any()))
                .thenAnswer(inv -> Mono.just((BigDecimal) inv.getArgument(0)));
        when(fxRateReader.convert(any(), eq("ZWL"), eq("USD"), any(), any()))
                .thenAnswer(inv -> Mono.just(((BigDecimal) inv.getArgument(0))
                        .multiply(new BigDecimal("0.02"))));

        StepVerifier.create(composer.lossRatio(REQ))
                .assertNext(env -> {
                    // Composite numerator: 60 + 3200*0.02 = 60 + 64 = 124 USD
                    // Composite denominator: 100 + 5000*0.02 = 100 + 100 = 200 USD
                    // Composite ratio: 124/200 = 0.62
                    assertThat(env.data().compositeNumerator()).isEqualByComparingTo("124.00");
                    assertThat(env.data().compositeDenominator()).isEqualByComparingTo("200.00");
                    assertThat(env.data().compositeRatio()).isEqualByComparingTo("0.620000");
                    assertThat(env.data().perCurrency()).containsKeys("USD", "ZWL");
                    // Per-currency stays native
                    assertThat(env.data().perCurrency().get("ZWL").ratio()).isEqualByComparingTo("0.640000");
                })
                .verifyComplete();
    }

    @Test
    void expenseRatio_insuranceLineFilter_appendsCoverageWarning() {
        KpiRequest lineFiltered = new KpiRequest(TENANT_ID, PERIOD_START, PERIOD_END,
                "USD", "HEALTH", null, null);
        when(commissionAggregateService.aggregatePaid(any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(List.of(commissionRow("USD", "HEALTH", new BigDecimal("12")))));
        when(contributionsClient.aggregateBilling(any(), any()))
                .thenReturn(Mono.just(List.of(new BillingAggregateRow(
                        UUID.randomUUID(), "Scheme A", "USD", new BigDecimal("100")))));

        StepVerifier.create(composer.expenseRatio(lineFiltered))
                .assertNext(env -> {
                    assertThat(env.warnings()).anyMatch(w -> w.contains("EXPENSE_RATIO denominator"));
                    // composite = 12/100
                    assertThat(env.data().compositeRatio()).isEqualByComparingTo("0.120000");
                })
                .verifyComplete();
    }

    @Test
    void combinedRatio_sumsLossAndExpenseWithMixedBasisNote() {
        // LR = 60/100 = 0.6
        when(claimsClient.claimsIncurred(any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(List.of(claimsRow("USD", "HEALTH",
                        new BigDecimal("60"), BigDecimal.ZERO, 5))));
        when(contributionsClient.earnedPremium(any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(List.of(earnedRow("USD", "HEALTH",
                        new BigDecimal("100"), 12))));
        // ER = 12/100 = 0.12
        when(commissionAggregateService.aggregatePaid(any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(List.of(commissionRow("USD", "HEALTH", new BigDecimal("12")))));
        when(contributionsClient.aggregateBilling(any(), any()))
                .thenReturn(Mono.just(List.of(new BillingAggregateRow(
                        UUID.randomUUID(), "Scheme A", "USD", new BigDecimal("100")))));

        StepVerifier.create(composer.combinedRatio(REQ))
                .assertNext(env -> {
                    assertThat(env.data().basisNote()).isEqualTo("MIXED_LOSS_EARNED_EXPENSE_WRITTEN");
                    // 0.6 + 0.12 = 0.72
                    assertThat(env.data().compositeRatio()).isEqualByComparingTo("0.720000");
                })
                .verifyComplete();
    }

    @Test
    void claimsFrequency_dimensionlessCountOverMonths() {
        when(claimsClient.claimsIncurred(any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(List.of(
                        claimsRow("USD", "HEALTH", new BigDecimal("300"), BigDecimal.ZERO, 6))));
        when(contributionsClient.earnedPremium(any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(List.of(earnedRow("USD", "HEALTH",
                        new BigDecimal("100"), 120))));

        StepVerifier.create(composer.claimsFrequency(REQ))
                .assertNext(env -> {
                    // 6 claims / 120 policy-months = 0.05
                    assertThat(env.data().compositeRatio()).isEqualByComparingTo("0.050000");
                    assertThat(env.data().compositeNumerator()).isEqualByComparingTo("6");
                    assertThat(env.data().compositeDenominator()).isEqualByComparingTo("120");
                })
                .verifyComplete();
    }

    @Test
    void averageSeverity_paidOverClaimCountPerCurrency() {
        when(claimsClient.claimsIncurred(any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(List.of(
                        claimsRow("USD", "HEALTH", new BigDecimal("2250"), BigDecimal.ZERO, 5))));

        StepVerifier.create(composer.averageSeverity(REQ))
                .assertNext(env -> {
                    // 2250 / 5 = 450
                    assertThat(env.data().compositeRatio()).isEqualByComparingTo("450.000000");
                    assertThat(env.data().perCurrency().get("USD").numerator())
                            .isEqualByComparingTo("2250");
                    assertThat(env.data().perCurrency().get("USD").denominator())
                            .isEqualByComparingTo("5");
                })
                .verifyComplete();
    }

    @Test
    void smallDenominator_belowThreshold_appendsWarning() {
        when(claimsClient.claimsIncurred(any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(List.of(claimsRow("USD", "HEALTH",
                        new BigDecimal("60"), BigDecimal.ZERO, 5))));
        when(contributionsClient.earnedPremium(any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(List.of(earnedRow("USD", "HEALTH",
                        new BigDecimal("500"), 6))));  // 500 < 1000 threshold

        StepVerifier.create(composer.lossRatio(REQ))
                .assertNext(env ->
                        assertThat(env.warnings()).anyMatch(w -> w.contains("below noise threshold")))
                .verifyComplete();
    }

    @Test
    void cacheHit_skipsPeerFanout() {
        // Warm the cache manually — return same data twice.
        KpiReportData cached = new KpiReportData(
                new BigDecimal("0.500000"), new BigDecimal("50"), new BigDecimal("100"),
                null, java.util.Map.of());
        when(valueOps.get(anyString())).thenReturn(Mono.just(cached));

        StepVerifier.create(composer.lossRatio(REQ))
                .assertNext(env -> assertThat(env.data().compositeRatio()).isEqualByComparingTo("0.500000"))
                .verifyComplete();

        verifyNoInteractions(claimsClient);
        verifyNoInteractions(contributionsClient);
        verifyNoInteractions(ibnrLookupService);
        verify(valueOps, times(0)).set(anyString(), any(), any(Duration.class));
    }

    @Test
    void cacheMiss_populatesCacheThenServes() {
        when(claimsClient.claimsIncurred(any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(List.of(claimsRow("USD", "HEALTH",
                        new BigDecimal("60"), BigDecimal.ZERO, 3))));
        when(contributionsClient.earnedPremium(any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(List.of(earnedRow("USD", "HEALTH",
                        new BigDecimal("100"), 12))));

        StepVerifier.create(composer.lossRatio(REQ)).expectNextCount(1).verifyComplete();

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<KpiReportData> valueCaptor = ArgumentCaptor.forClass(KpiReportData.class);
        verify(valueOps).set(keyCaptor.capture(), valueCaptor.capture(), eq(Duration.ofMinutes(15)));
        assertThat(keyCaptor.getValue()).contains("kpi:").contains("LOSS_RATIO_KPI");
        assertThat(valueCaptor.getValue().compositeRatio()).isEqualByComparingTo("0.600000");
    }

    @Test
    void peerFailure_populatesWarningsAndContinuesWithPartialData() {
        when(claimsClient.claimsIncurred(any(), any(), any(), any(), any()))
                .thenReturn(Mono.error(new RuntimeException("claims-service DOWN")));
        when(contributionsClient.earnedPremium(any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(List.of(earnedRow("USD", "HEALTH",
                        new BigDecimal("100"), 12))));

        StepVerifier.create(composer.lossRatio(REQ))
                .assertNext(env -> {
                    assertThat(env.warnings()).anyMatch(w -> w.contains("claims-incurred"));
                    // Numerator = 0 (peer down), denominator = 100 → 0
                    assertThat(env.data().compositeRatio()).isEqualByComparingTo("0.000000");
                })
                .verifyComplete();
    }

    @Test
    void dashboard_disabledKey_returns403() {
        when(reportEnablementReader.isEnabled(any(), eq(ReportKey.EXPENSE_RATIO)))
                .thenReturn(Mono.just(false));

        StepVerifier.create(composer.dashboard(REQ))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(ResponseStatusException.class);
                    assertThat(((ResponseStatusException) err).getStatusCode().value()).isEqualTo(403);
                    assertThat(err.getMessage()).contains("EXPENSE_RATIO");
                })
                .verify();
    }

    @Test
    void dashboard_allEnabled_returnsFiveTiles() {
        // All peers return empty (zero); the batch still zips 5 envelopes.
        when(claimsClient.claimsIncurred(any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(List.of()));
        when(contributionsClient.earnedPremium(any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(List.of()));
        when(contributionsClient.aggregateBilling(any(), any()))
                .thenReturn(Mono.just(List.of()));
        when(commissionAggregateService.aggregatePaid(any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(List.of()));

        StepVerifier.create(composer.dashboard(REQ))
                .assertNext(dashboard -> {
                    assertThat(dashboard.tiles()).hasSize(5)
                            .containsKeys("LOSS_RATIO_KPI", "EXPENSE_RATIO", "COMBINED_RATIO",
                                    "CLAIMS_FREQUENCY", "AVERAGE_SEVERITY");
                    ReportResponse<KpiReportData> combined = dashboard.tiles().get("COMBINED_RATIO");
                    assertThat(combined.data().basisNote()).isEqualTo("MIXED_LOSS_EARNED_EXPENSE_WRITTEN");
                })
                .verifyComplete();
    }

    @Test
    void trend_windowMonths12_returns12BucketsOldestFirst() {
        // Anchor = first-of-month 2026-08-01 → bucket-0 = [2025-08-01, 2025-09-01),
        // bucket-11 = [2026-07-01, 2026-08-01).
        KpiRequest anchoredReq = new KpiRequest(TENANT_ID,
                java.time.LocalDate.of(2025, 8, 1),
                java.time.LocalDate.of(2026, 8, 1),
                "USD", null, null, null);
        when(claimsClient.claimsIncurred(any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(List.of(claimsRow("USD", "HEALTH",
                        new BigDecimal("60"), BigDecimal.ZERO, 5))));
        when(contributionsClient.earnedPremium(any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(List.of(earnedRow("USD", "HEALTH",
                        new BigDecimal("100"), 12))));

        StepVerifier.create(composer.trend(ReportKey.LOSS_RATIO_KPI, anchoredReq, 12))
                .assertNext(trend -> {
                    assertThat(trend).hasSize(12);
                    assertThat(trend.get(0).periodStart()).isEqualTo("2025-08-01");
                    assertThat(trend.get(0).periodEnd()).isEqualTo("2025-09-01");
                    assertThat(trend.get(11).periodStart()).isEqualTo("2026-07-01");
                    assertThat(trend.get(11).periodEnd()).isEqualTo("2026-08-01");
                    assertThat(trend.get(0).composite().compositeRatio()).isEqualByComparingTo("0.600000");
                })
                .verifyComplete();
    }

    @Test
    void trend_windowMonths24_returns24Buckets() {
        KpiRequest anchoredReq = new KpiRequest(TENANT_ID,
                java.time.LocalDate.of(2024, 8, 1),
                java.time.LocalDate.of(2026, 8, 1),
                "USD", null, null, null);
        when(claimsClient.claimsIncurred(any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(List.of()));
        when(contributionsClient.earnedPremium(any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(List.of()));

        StepVerifier.create(composer.trend(ReportKey.LOSS_RATIO_KPI, anchoredReq, 24))
                .assertNext(trend -> assertThat(trend).hasSize(24))
                .verifyComplete();
    }

    @Test
    void trend_invalidWindowMonths_errors() {
        StepVerifier.create(composer.trend(ReportKey.LOSS_RATIO_KPI, REQ, 17))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(IllegalArgumentException.class);
                    assertThat(err.getMessage()).contains("12 or 24");
                })
                .verify();
    }

    @Test
    void trend_nonDashboardKey_errors() {
        StepVerifier.create(composer.trend(ReportKey.BILLING_REPORT, REQ, 12))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(IllegalArgumentException.class);
                    assertThat(err.getMessage()).contains("Not a dashboard KPI key");
                })
                .verify();
    }

    @Test
    void trend_cachedBucket_skipsFanoutForThatBucket() {
        // Warm the cache with a fixed KpiReportData — every bucket returns from cache.
        KpiReportData cached = new KpiReportData(
                new BigDecimal("0.550000"), new BigDecimal("55"), new BigDecimal("100"),
                null, java.util.Map.of("USD",
                        new com.medfund.finance.kpi.dto.KpiValue(
                                new BigDecimal("0.550000"), new BigDecimal("55"),
                                new BigDecimal("100"), "USD")));
        when(valueOps.get(anyString())).thenReturn(Mono.just(cached));
        KpiRequest anchoredReq = new KpiRequest(TENANT_ID,
                java.time.LocalDate.of(2025, 8, 1),
                java.time.LocalDate.of(2026, 8, 1),
                "USD", null, null, null);

        StepVerifier.create(composer.trend(ReportKey.LOSS_RATIO_KPI, anchoredReq, 12))
                .assertNext(trend -> {
                    assertThat(trend).hasSize(12);
                    assertThat(trend).allSatisfy(p ->
                            assertThat(p.composite().compositeRatio()).isEqualByComparingTo("0.550000"));
                })
                .verifyComplete();

        verifyNoInteractions(claimsClient);
        verifyNoInteractions(contributionsClient);
    }

    // ── Fixture helpers ──────────────────────────────────────────────────────

    private static ClaimsIncurredAggregateRow claimsRow(String currency, String line,
                                                        BigDecimal subtotalIncurredExIbnr,
                                                        BigDecimal reserveMovement, long claimCount) {
        BigDecimal totalPaid = subtotalIncurredExIbnr.subtract(reserveMovement);
        return new ClaimsIncurredAggregateRow(
                UUID.randomUUID(), "Scheme A", line, currency,
                totalPaid, BigDecimal.ZERO, reserveMovement, reserveMovement,
                subtotalIncurredExIbnr, claimCount);
    }

    private static PremiumEarnedAggregateRow earnedRow(String currency, String line,
                                                       BigDecimal earned, long rowCount) {
        return new PremiumEarnedAggregateRow(
                UUID.randomUUID(), "Scheme A", line, currency, earned, rowCount);
    }

    private static CommissionAggregateRow commissionRow(String currency, String line, BigDecimal paid) {
        return new CommissionAggregateRow(
                UUID.randomUUID(), "Ace Brokers", line, currency, paid, 1L);
    }

    private static AggregateDimension dimensionMatch(String expected) {
        return AggregateDimension.valueOf(expected);
    }
}
