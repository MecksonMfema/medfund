package com.medfund.shared.report.regulatory;

import com.medfund.shared.report.ReportKey;

import java.util.Optional;

/**
 * Static registry that decides the ISO-4217 currency a Phase-16 regulator
 * report must be filed in. Two axes:
 *
 * <ul>
 *   <li>{@link #fixedFor(ReportKey)} — the report has a single fixed
 *       currency dictated by the regulator regardless of tenant country
 *       (e.g. IPEC only accepts ZWL, NAIC only USD). Returns
 *       {@link Optional#empty()} for country-native reports.</li>
 *   <li>{@link #countryNativeFor(ReportKey, String)} — the report is filed
 *       in the tenant's own country's currency (e.g. VAT return in ZW is
 *       ZWL, in ZA is ZAR). Returns empty for fixed-currency reports.</li>
 * </ul>
 *
 * <p>{@link #resolveOrThrow(ReportKey, String)} is the ergonomic
 * "resolve one or the other, error if neither" entry point that the
 * shaping service uses. A tenant override is not honoured — Phase 16
 * regulator reports never let the caller pick the currency; the
 * shape-service entry point rejects a client override with 422.
 */
public final class RegulatoryReportCurrency {

    private RegulatoryReportCurrency() {}

    /** Fixed-currency reports (regulator dictates a single currency). */
    public static Optional<String> fixedFor(ReportKey key) {
        return switch (key) {
            case IPEC_QUARTERLY_RETURN -> Optional.of("ZWL");
            case CMS_ASR, PMB_SPEND -> Optional.of("ZAR");
            case NAIC_SCHEDULE_P, NAIC_SCHEDULE_F -> Optional.of("USD");
            default -> Optional.empty();
        };
    }

    /**
     * Country-native reports (currency follows the tenant's own country).
     * Returns empty for fixed-currency reports or unknown countries.
     */
    public static Optional<String> countryNativeFor(ReportKey key, String countryCode) {
        if (!isCountryNative(key) || countryCode == null || countryCode.isBlank()) {
            return Optional.empty();
        }
        return switch (countryCode) {
            case "ZW" -> Optional.of("ZWL");
            case "ZA" -> Optional.of("ZAR");
            case "US" -> Optional.of("USD");
            default -> Optional.empty();
        };
    }

    /** True when the report's currency follows the tenant's country. */
    public static boolean isCountryNative(ReportKey key) {
        return switch (key) {
            case AML_STR, TAX_WITHHELD_RETURN, VAT_RETURN -> true;
            default -> false;
        };
    }

    /**
     * Resolve the reporting currency or throw when neither the fixed nor
     * the country-native map yields a value — a genuine data / config
     * error (unknown country for an AML/TAX/VAT tenant, or the key is
     * not a Phase-16 regulator report at all).
     */
    public static String resolveOrThrow(ReportKey key, String tenantCountryCode) {
        return fixedFor(key)
                .or(() -> countryNativeFor(key, tenantCountryCode))
                .orElseThrow(() -> new IllegalStateException(
                        "No regulator currency for " + key + " country " + tenantCountryCode));
    }
}
