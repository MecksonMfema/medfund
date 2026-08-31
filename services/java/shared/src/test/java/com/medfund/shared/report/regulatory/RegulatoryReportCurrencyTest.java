package com.medfund.shared.report.regulatory;

import com.medfund.shared.report.ReportKey;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegulatoryReportCurrencyTest {

    private static final List<ReportKey> PHASE_16_KEYS = List.of(
            ReportKey.IPEC_QUARTERLY_RETURN,
            ReportKey.CMS_ASR,
            ReportKey.NAIC_SCHEDULE_P,
            ReportKey.NAIC_SCHEDULE_F,
            ReportKey.PMB_SPEND,
            ReportKey.AML_STR,
            ReportKey.TAX_WITHHELD_RETURN,
            ReportKey.VAT_RETURN);

    @Test
    void fixedFor_ipecReturnsZwl() {
        assertThat(RegulatoryReportCurrency.fixedFor(ReportKey.IPEC_QUARTERLY_RETURN))
                .contains("ZWL");
    }

    @Test
    void fixedFor_cmsAndPmbReturnZar() {
        assertThat(RegulatoryReportCurrency.fixedFor(ReportKey.CMS_ASR)).contains("ZAR");
        assertThat(RegulatoryReportCurrency.fixedFor(ReportKey.PMB_SPEND)).contains("ZAR");
    }

    @Test
    void fixedFor_naicSchedulesReturnUsd() {
        assertThat(RegulatoryReportCurrency.fixedFor(ReportKey.NAIC_SCHEDULE_P)).contains("USD");
        assertThat(RegulatoryReportCurrency.fixedFor(ReportKey.NAIC_SCHEDULE_F)).contains("USD");
    }

    @Test
    void fixedFor_countryNativeReportsReturnEmpty() {
        assertThat(RegulatoryReportCurrency.fixedFor(ReportKey.VAT_RETURN)).isEmpty();
        assertThat(RegulatoryReportCurrency.fixedFor(ReportKey.TAX_WITHHELD_RETURN)).isEmpty();
        assertThat(RegulatoryReportCurrency.fixedFor(ReportKey.AML_STR)).isEmpty();
    }

    @Test
    void fixedFor_nonPhase16KeysReturnEmpty() {
        assertThat(RegulatoryReportCurrency.fixedFor(ReportKey.BILLING_REPORT)).isEmpty();
        assertThat(RegulatoryReportCurrency.fixedFor(ReportKey.IFRS17_LRC_LIC_RECONCILIATION)).isEmpty();
    }

    @Test
    void isCountryNative_flagsThreeReports() {
        assertThat(RegulatoryReportCurrency.isCountryNative(ReportKey.AML_STR)).isTrue();
        assertThat(RegulatoryReportCurrency.isCountryNative(ReportKey.VAT_RETURN)).isTrue();
        assertThat(RegulatoryReportCurrency.isCountryNative(ReportKey.TAX_WITHHELD_RETURN)).isTrue();
        // Fixed-currency reports must not be country-native as well.
        assertThat(RegulatoryReportCurrency.isCountryNative(ReportKey.IPEC_QUARTERLY_RETURN)).isFalse();
        assertThat(RegulatoryReportCurrency.isCountryNative(ReportKey.NAIC_SCHEDULE_P)).isFalse();
        assertThat(RegulatoryReportCurrency.isCountryNative(ReportKey.CMS_ASR)).isFalse();
    }

    @Test
    void countryNativeFor_zwZaUsResolveExpectedCurrencies() {
        assertThat(RegulatoryReportCurrency.countryNativeFor(ReportKey.VAT_RETURN, "ZW")).contains("ZWL");
        assertThat(RegulatoryReportCurrency.countryNativeFor(ReportKey.VAT_RETURN, "ZA")).contains("ZAR");
        assertThat(RegulatoryReportCurrency.countryNativeFor(ReportKey.AML_STR, "US")).contains("USD");
    }

    @Test
    void countryNativeFor_unknownCountryReturnsEmpty() {
        assertThat(RegulatoryReportCurrency.countryNativeFor(ReportKey.VAT_RETURN, "GB")).isEmpty();
        assertThat(RegulatoryReportCurrency.countryNativeFor(ReportKey.VAT_RETURN, null)).isEmpty();
        assertThat(RegulatoryReportCurrency.countryNativeFor(ReportKey.VAT_RETURN, "")).isEmpty();
    }

    @Test
    void countryNativeFor_fixedCurrencyReportReturnsEmpty() {
        // Even with ZW/ZA/US country codes, a fixed-currency report is fixed.
        assertThat(RegulatoryReportCurrency.countryNativeFor(ReportKey.IPEC_QUARTERLY_RETURN, "ZW")).isEmpty();
        assertThat(RegulatoryReportCurrency.countryNativeFor(ReportKey.CMS_ASR, "ZA")).isEmpty();
    }

    @Test
    void resolveOrThrow_everyPhase16KeyResolvableWithMatchingCountry() {
        // Fixed-currency reports resolve regardless of country; country-native reports use country.
        assertThat(RegulatoryReportCurrency.resolveOrThrow(ReportKey.IPEC_QUARTERLY_RETURN, null))
                .isEqualTo("ZWL");
        assertThat(RegulatoryReportCurrency.resolveOrThrow(ReportKey.NAIC_SCHEDULE_P, "US"))
                .isEqualTo("USD");
        assertThat(RegulatoryReportCurrency.resolveOrThrow(ReportKey.VAT_RETURN, "ZW"))
                .isEqualTo("ZWL");
        assertThat(RegulatoryReportCurrency.resolveOrThrow(ReportKey.AML_STR, "US"))
                .isEqualTo("USD");
    }

    @Test
    void resolveOrThrow_countryNativeReportWithNoCountry_throws() {
        assertThatThrownBy(() -> RegulatoryReportCurrency.resolveOrThrow(ReportKey.VAT_RETURN, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("VAT_RETURN");
    }

    @Test
    void resolveOrThrow_nonPhase16Key_throws() {
        assertThatThrownBy(() -> RegulatoryReportCurrency.resolveOrThrow(ReportKey.BILLING_REPORT, "ZW"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void everyPhase16KeyEitherFixedOrCountryNative() {
        // Structural invariant: no Phase-16 key falls through the cracks.
        for (ReportKey key : PHASE_16_KEYS) {
            boolean fixed = RegulatoryReportCurrency.fixedFor(key).isPresent();
            boolean countryNative = RegulatoryReportCurrency.isCountryNative(key);
            assertThat(fixed || countryNative)
                    .as("Phase-16 key %s must be either fixed or country-native", key)
                    .isTrue();
            // And exclusive — fixed-currency reports must not also be country-native.
            assertThat(fixed && countryNative).isFalse();
        }
    }
}
