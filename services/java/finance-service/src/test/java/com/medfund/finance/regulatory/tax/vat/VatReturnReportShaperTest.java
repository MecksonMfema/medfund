package com.medfund.finance.regulatory.tax.vat;

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

class VatReturnReportShaperTest {

    private static final UUID TENANT = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final LocalDate PERIOD_START = LocalDate.of(2026, 4, 1);
    private static final LocalDate PERIOD_END   = LocalDate.of(2026, 6, 30);

    private final VatCalculator calculator = new VatCalculator();
    private final VatReturnReportShaper shaper = new VatReturnReportShaper(
            (tenantId, ps, pe) -> Mono.just(goldenRaw()),
            (tenantId, country, currency, asOf) -> Mono.just(goldenRates()),
            calculator);

    @Test
    void supportedKey_isVatReturn() {
        assertThat(shaper.supportedKey()).isEqualTo(ReportKey.VAT_RETURN);
    }

    @Test
    void shape_producesEveryExpectedSection_withCountryNativeCurrency() {
        StepVerifier.create(shaper.shape(TENANT, PERIOD_START, PERIOD_END, "ZA"))
                .assertNext(data -> {
                    assertThat(data.reportKey()).isEqualTo(ReportKey.VAT_RETURN);
                    assertThat(data.reportingCurrency()).isEqualTo("ZAR");
                    assertThat(data.sections()).containsOnlyKeys(
                            VatReturnReportShaper.SECTION_META,
                            VatReturnReportShaper.SECTION_OUTPUT,
                            VatReturnReportShaper.SECTION_INPUT,
                            VatReturnReportShaper.SECTION_SUMMARY);
                    assertThat(data.sections().get(VatReturnReportShaper.SECTION_META)
                            .get(VatField.META_COUNTRY.name())).isEqualTo("ZA");
                })
                .verifyComplete();
    }

    @Test
    void shape_zwTenant_yieldsZwlCurrency() {
        StepVerifier.create(shaper.shape(TENANT, PERIOD_START, PERIOD_END, "ZW"))
                .assertNext(data -> {
                    assertThat(data.reportingCurrency()).isEqualTo("ZWL");
                    assertThat(data.sections().get(VatReturnReportShaper.SECTION_META)
                            .get(VatField.META_COUNTRY.name())).isEqualTo("ZW");
                })
                .verifyComplete();
    }

    @Test
    void compose_populatesGoldenValues_exactly() {
        RegulatoryReportData data = shaper.compose(goldenRaw(), goldenRates(),
                TENANT, "ZA", PERIOD_START, PERIOD_END, "ZAR");

        Map<String, Object> output = data.sections().get(VatReturnReportShaper.SECTION_OUTPUT);
        assertThat((BigDecimal) output.get(VatField.OUTPUT_PREMIUM_VAT.name()))
                .isEqualByComparingTo("0.00");
        assertThat((BigDecimal) output.get(VatField.OUTPUT_ADMIN_FEE_VAT.name()))
                .isEqualByComparingTo("1200000.00");
        assertThat((BigDecimal) output.get(VatField.OUTPUT_STANDARD_RATED_TOTAL_VAT.name()))
                .isEqualByComparingTo("1875000.00");
        assertThat((BigDecimal) output.get(VatField.OUTPUT_ZERO_RATED_TOTAL_BASE.name()))
                .isEqualByComparingTo("100000000.00");

        Map<String, Object> input = data.sections().get(VatReturnReportShaper.SECTION_INPUT);
        assertThat((BigDecimal) input.get(VatField.INPUT_TOTAL_VAT.name()))
                .isEqualByComparingTo("495000.00");

        Map<String, Object> summary = data.sections().get(VatReturnReportShaper.SECTION_SUMMARY);
        assertThat((BigDecimal) summary.get(VatField.SUMMARY_NET_VAT_PAYABLE.name()))
                .isEqualByComparingTo("1380000.00");
    }

    @Test
    void toCellValueMap_isRoundTripAgainstCompose() {
        RegulatoryReportData data = shaper.compose(goldenRaw(), goldenRates(),
                TENANT, "ZA", PERIOD_START, PERIOD_END, "ZAR");

        Map<VatField, Object> flat = VatReturnReportShaper.toCellValueMap(data);

        assertThat(flat).containsKeys(VatField.values());
        assertThat((BigDecimal) flat.get(VatField.SUMMARY_NET_VAT_PAYABLE))
                .isEqualByComparingTo("1380000.00");
        assertThat(flat.get(VatField.META_REPORTING_CURRENCY)).isEqualTo("ZAR");
    }

    static VatRawData goldenRaw() {
        return new VatRawData(
                "Acme Insurance ZA (Pty) Ltd",
                "4900123456",
                new BigDecimal("100000000.00"),
                new BigDecimal("8000000.00"),
                new BigDecimal("4000000.00"),
                new BigDecimal("500000.00"),
                new BigDecimal("2000000.00"),
                new BigDecimal("1000000.00"),
                new BigDecimal("300000.00"));
    }

    static VatRates goldenRates() {
        return new VatRates(
                new BigDecimal("0.00"),
                new BigDecimal("0.15"),
                new BigDecimal("0.15"),
                new BigDecimal("0.15"));
    }
}
