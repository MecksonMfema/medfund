package com.medfund.finance.regulatory.naic;

import com.medfund.finance.regulatory.service.RegulatoryParameterResolver;
import com.medfund.finance.regulatory.service.RegulatoryReportData;
import com.medfund.shared.report.ReportKey;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NaicScheduleFReportShaperTest {

    private static final UUID TENANT = UUID.fromString("77777777-7777-7777-7777-777777777777");
    private static final LocalDate PERIOD_START = LocalDate.of(2026, 1, 1);
    private static final LocalDate PERIOD_END   = LocalDate.of(2026, 12, 31);

    private final NaicScheduleFCalculator calculator = new NaicScheduleFCalculator();
    private final NaicScheduleFReportShaper shaper = new NaicScheduleFReportShaper(
            (tenantId, ps, pe) -> Mono.just(goldenRaw()),
            calculator);

    @Test
    void supportedKey_isNaicScheduleF() {
        assertThat(shaper.supportedKey()).isEqualTo(ReportKey.NAIC_SCHEDULE_F);
    }

    @Test
    void shape_producesEveryExpectedSection_withUsdCurrency() {
        StepVerifier.create(shaper.shape(TENANT, PERIOD_START, PERIOD_END, "US"))
                .assertNext(data -> {
                    assertThat(data.reportKey()).isEqualTo(ReportKey.NAIC_SCHEDULE_F);
                    assertThat(data.reportingCurrency()).isEqualTo("USD");
                    assertThat(data.sections()).containsOnlyKeys(
                            NaicScheduleFReportShaper.SECTION_META,
                            NaicScheduleFReportShaper.SECTION_ASSUMED,
                            NaicScheduleFReportShaper.SECTION_CEDED_AFFILIATED,
                            NaicScheduleFReportShaper.SECTION_CEDED_AUTHORIZED,
                            NaicScheduleFReportShaper.SECTION_CEDED_UNAUTHORIZED,
                            NaicScheduleFReportShaper.SECTION_CEDED_CERTIFIED,
                            NaicScheduleFReportShaper.SECTION_TOTALS);
                })
                .verifyComplete();
    }

    @Test
    void compose_populatesGoldenValues_exactly() {
        RegulatoryReportData data = shaper.compose(goldenRaw(), TENANT,
                PERIOD_START, PERIOD_END, "USD");

        Map<String, Object> assumed = data.sections().get(NaicScheduleFReportShaper.SECTION_ASSUMED);
        assertThat((BigDecimal) assumed.get(NaicFField.P1_ASSUMED_PREMIUMS.name()))
                .isEqualByComparingTo("15000000.00");

        Map<String, Object> unauthorized = data.sections().get(NaicScheduleFReportShaper.SECTION_CEDED_UNAUTHORIZED);
        assertThat((BigDecimal) unauthorized.get(NaicFField.P4_CEDED_UNAUTHORIZED_LOSSES_PAID.name()))
                .isEqualByComparingTo("2000000.00");
        assertThat((BigDecimal) unauthorized.get(NaicFField.P4_CEDED_UNAUTHORIZED_LOSSES_UNPAID.name()))
                .isEqualByComparingTo("1000000.00");

        Map<String, Object> totals = data.sections().get(NaicScheduleFReportShaper.SECTION_TOTALS);
        assertThat((BigDecimal) totals.get(NaicFField.TOTAL_CEDED_PREMIUMS.name()))
                .isEqualByComparingTo("48000000.00");
        assertThat((BigDecimal) totals.get(NaicFField.TOTAL_CEDED_LOSSES_PAID.name()))
                .isEqualByComparingTo("22000000.00");
        assertThat((BigDecimal) totals.get(NaicFField.TOTAL_CEDED_LOSSES_UNPAID.name()))
                .isEqualByComparingTo("14000000.00");
        assertThat((BigDecimal) totals.get(NaicFField.TOTAL_REINSURANCE_RECOVERABLE.name()))
                .isEqualByComparingTo("36000000.00");
        assertThat((BigDecimal) totals.get(NaicFField.PROVISION_FOR_REINSURANCE.name()))
                .isEqualByComparingTo("4000000.00");
        assertThat((BigDecimal) totals.get(NaicFField.NET_REINSURANCE_POSITION.name()))
                .isEqualByComparingTo("32000000.00");
    }

    @Test
    void shape_resolverOverride_flowsRuleValuesIntoProvision() {
        // Phase 15b — a tenant rule that doubles the certified reinsurer
        // provision percentage from 0.20 → 0.40 shifts the ProvisionForReinsurance
        // and NetReinsurancePosition proportionally to the certified stratum's
        // recoverable (10M) × delta 0.20 = 2M.
        RegulatoryParameterResolver resolver = mock(RegulatoryParameterResolver.class);
        when(resolver.resolve(eq(TENANT), eq("US_NAIC"),
                eq(NaicScheduleFParameters.KEY_UNAUTHORIZED_PROVISION_PCT), any(LocalDate.class)))
                .thenReturn(Mono.just(new BigDecimal("1.00")));
        when(resolver.resolve(eq(TENANT), eq("US_NAIC"),
                eq(NaicScheduleFParameters.KEY_CERTIFIED_PROVISION_PCT), any(LocalDate.class)))
                .thenReturn(Mono.just(new BigDecimal("0.40")));
        NaicScheduleFReportShaper resolverShaper = new NaicScheduleFReportShaper(
                (t, ps, pe) -> Mono.just(goldenRaw()), calculator, resolver);

        StepVerifier.create(resolverShaper.shape(TENANT, PERIOD_START, PERIOD_END, "US"))
                .assertNext(data -> {
                    Map<String, Object> totals = data.sections().get(NaicScheduleFReportShaper.SECTION_TOTALS);
                    // Certified recoverable (5M) × 0.40 = 2M provision (vs 1M at 0.20).
                    // Unauthorized recoverable (3M) × 1.00 = 3M. Total = 5M provision (was 4M).
                    assertThat((BigDecimal) totals.get(NaicFField.PROVISION_FOR_REINSURANCE.name()))
                            .isEqualByComparingTo("5000000.00");
                    // NetReinsurancePosition = 36M recoverable − 5M provision = 31M.
                    assertThat((BigDecimal) totals.get(NaicFField.NET_REINSURANCE_POSITION.name()))
                            .isEqualByComparingTo("31000000.00");
                })
                .verifyComplete();
    }

    @Test
    void toCellValueMap_isRoundTripAgainstCompose() {
        RegulatoryReportData data = shaper.compose(goldenRaw(), TENANT,
                PERIOD_START, PERIOD_END, "USD");

        Map<NaicFField, Object> flat = NaicScheduleFReportShaper.toCellValueMap(data);

        assertThat(flat).containsKeys(NaicFField.values());
        assertThat((BigDecimal) flat.get(NaicFField.NET_REINSURANCE_POSITION))
                .isEqualByComparingTo("32000000.00");
        assertThat(flat.get(NaicFField.META_REPORTING_CURRENCY)).isEqualTo("USD");
    }

    static NaicScheduleFRawData goldenRaw() {
        return new NaicScheduleFRawData(
                "Acme Insurance US",
                "12345",
                "0999",
                "12-3456789",
                "IL",
                // assumed
                new BigDecimal("15000000.00"),
                new BigDecimal("8000000.00"),
                new BigDecimal("4000000.00"),
                // ceded affiliated
                new BigDecimal("10000000.00"),
                new BigDecimal("5000000.00"),
                new BigDecimal("3000000.00"),
                // ceded authorized
                new BigDecimal("25000000.00"),
                new BigDecimal("12000000.00"),
                new BigDecimal("8000000.00"),
                // ceded unauthorized
                new BigDecimal("5000000.00"),
                new BigDecimal("2000000.00"),
                new BigDecimal("1000000.00"),
                // ceded certified
                new BigDecimal("8000000.00"),
                new BigDecimal("3000000.00"),
                new BigDecimal("2000000.00"));
    }
}
