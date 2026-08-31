package com.medfund.finance.regulatory.tax.wht;

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

class TaxWithheldReturnReportShaperTest {

    private static final UUID TENANT = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");
    private static final LocalDate PS = LocalDate.of(2026, 4, 1);
    private static final LocalDate PE = LocalDate.of(2026, 6, 30);

    private final TaxWithheldCalculator calc = new TaxWithheldCalculator();
    private final TaxWithheldReturnReportShaper shaper = new TaxWithheldReturnReportShaper(
            (tenantId, ps, pe) -> Mono.just(goldenRaw()),
            (tenantId, country, currency, asOf) -> Mono.just(goldenRates()),
            calc);

    @Test
    void supportedKey_isTaxWithheldReturn() {
        assertThat(shaper.supportedKey()).isEqualTo(ReportKey.TAX_WITHHELD_RETURN);
    }

    @Test
    void shape_zaTenant_yieldsZarCurrency_andEveryExpectedSection() {
        StepVerifier.create(shaper.shape(TENANT, PS, PE, "ZA"))
                .assertNext(data -> {
                    assertThat(data.reportKey()).isEqualTo(ReportKey.TAX_WITHHELD_RETURN);
                    assertThat(data.reportingCurrency()).isEqualTo("ZAR");
                    assertThat(data.sections()).containsOnlyKeys(
                            TaxWithheldReturnReportShaper.SECTION_META,
                            TaxWithheldReturnReportShaper.SECTION_CATEGORIES,
                            TaxWithheldReturnReportShaper.SECTION_TOTALS);
                })
                .verifyComplete();
    }

    @Test
    void shape_zwTenant_yieldsZwlCurrency() {
        StepVerifier.create(shaper.shape(TENANT, PS, PE, "ZW"))
                .assertNext(data -> assertThat(data.reportingCurrency()).isEqualTo("ZWL"))
                .verifyComplete();
    }

    @Test
    void compose_populatesGoldenValues_exactly() {
        RegulatoryReportData data = shaper.compose(goldenRaw(), goldenRates(),
                TENANT, "ZA", PS, PE, "ZAR");

        Map<String, Object> cats = data.sections().get(TaxWithheldReturnReportShaper.SECTION_CATEGORIES);
        assertThat((BigDecimal) cats.get(TaxWithheldField.CAT_COMMISSION_WHT.name()))
                .isEqualByComparingTo("600000.00");
        assertThat((BigDecimal) cats.get(TaxWithheldField.CAT_PROFESSIONAL_FEES_WHT.name()))
                .isEqualByComparingTo("180000.00");
        assertThat((BigDecimal) cats.get(TaxWithheldField.CAT_DIVIDENDS_WHT.name()))
                .isEqualByComparingTo("75000.00");
        assertThat((BigDecimal) cats.get(TaxWithheldField.CAT_OTHER_WHT.name()))
                .isEqualByComparingTo("15000.00");

        Map<String, Object> tot = data.sections().get(TaxWithheldReturnReportShaper.SECTION_TOTALS);
        assertThat((BigDecimal) tot.get(TaxWithheldField.TOTAL_PAYMENTS_BASE.name()))
                .isEqualByComparingTo("5800000.00");
        assertThat((BigDecimal) tot.get(TaxWithheldField.TOTAL_WITHHOLDING_PAYABLE.name()))
                .isEqualByComparingTo("870000.00");
    }

    @Test
    void toCellValueMap_isRoundTripAgainstCompose() {
        RegulatoryReportData data = shaper.compose(goldenRaw(), goldenRates(),
                TENANT, "ZA", PS, PE, "ZAR");

        Map<TaxWithheldField, Object> flat = TaxWithheldReturnReportShaper.toCellValueMap(data);

        assertThat(flat).containsKeys(TaxWithheldField.values());
        assertThat((BigDecimal) flat.get(TaxWithheldField.TOTAL_WITHHOLDING_PAYABLE))
                .isEqualByComparingTo("870000.00");
        assertThat(flat.get(TaxWithheldField.META_REPORTING_CURRENCY)).isEqualTo("ZAR");
    }

    static TaxWithheldRawData goldenRaw() {
        return new TaxWithheldRawData(
                "Acme Insurance ZA (Pty) Ltd",
                "9012345678",
                new BigDecimal("4000000.00"), BigDecimal.ZERO,
                new BigDecimal("1200000.00"), BigDecimal.ZERO,
                new BigDecimal("500000.00"),  BigDecimal.ZERO,
                new BigDecimal("100000.00"),  BigDecimal.ZERO);
    }

    static TaxWithheldRates goldenRates() {
        return new TaxWithheldRates(
                new BigDecimal("0.15"),
                new BigDecimal("0.15"),
                new BigDecimal("0.15"),
                new BigDecimal("0.15"));
    }
}
