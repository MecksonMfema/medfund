package com.medfund.shared.report.regulatory;

import com.medfund.shared.report.ReportKey;

import java.util.Optional;

/**
 * Static registry that decides the ISO-4217 currency a Phase-16 regulator
 * report must be filed in. Two axes:
 *
 * <ul>
 *   <li>{@link #fixedFor(ReportKey)} the report has a single fixed
 *       currency dictated by the regulator regardless of tenant country
 *       (e.g. IPEC only accepts ZWL, CMS only ZAR). Returns
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
            // Restored after 456726d3 removed it as collateral damage of the NAIC
            // prudential-returns cleanup. AML_STR still admits US — see
            // AmlStrReportController @RequiresCountry({"ZW","ZA","US"}).
            case "US" -> Optional.of("USD");
            default -> Optional.empty();
        };
    }

    /**
     * True when the report's currency follows the tenant's country by
     * default. VAT + WHT retain this classification for the picker's
     * fallback path (empty pick → country default) even though they also
     * opt into {@link #supportsCurrencyOverride} so the picker itself can
     * override.
     */
    public static boolean isCountryNative(ReportKey key) {
        return switch (key) {
            case AML_STR, TAX_WITHHELD_RETURN, VAT_RETURN -> true;
            default -> false;
        };
    }

    /**
     * True when the caller may pick the reporting currency via a picker.
     * VAT and WHT tenants may operate in multiple currencies (e.g. a
     * Zimbabwe operation running ZWG and USD side-by-side) and file
     * separate returns per currency, so the report scopes to whatever
     * the picker selects rather than a single country-forced currency.
     */
    public static boolean supportsCurrencyOverride(ReportKey key) {
        return switch (key) {
            case TAX_WITHHELD_RETURN, VAT_RETURN -> true;
            default -> false;
        };
    }

    /**
     * Resolve the reporting currency or throw when neither the fixed nor
     * the country-native map yields a value — a genuine data / config
     * error (unknown country for an AML tenant, or the key is not a
     * Phase-16 regulator report at all).
     */
    public static String resolveOrThrow(ReportKey key, String tenantCountryCode) {
        return fixedFor(key)
                .or(() -> countryNativeFor(key, tenantCountryCode))
                .orElseThrow(() -> new IllegalStateException(
                        "No regulator currency for " + key + " country " + tenantCountryCode));
    }

    /**
     * Resolve honouring a client picker. When the report supports override
     * and the caller supplies a non-blank currency, it wins. Otherwise
     * falls back to {@link #resolveOrThrow(ReportKey, String)}.
     */
    public static String resolveWithOverride(ReportKey key, String override, String tenantCountryCode) {
        if (override != null && !override.isBlank() && supportsCurrencyOverride(key)) {
            return override.trim().toUpperCase();
        }
        return resolveOrThrow(key, tenantCountryCode);
    }
}
