package com.medfund.finance.regulatory.aml;

import com.medfund.finance.regulatory.aml.entity.SuspiciousTransactionAlert;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * Shapes a {@link SuspiciousTransactionAlert} + tenant country tuple into a
 * per-{@link AmlStrField} value map that {@link AmlStrFilingXlsxService}
 * writes into the country-specific filing template.
 *
 * <p>Unlike {@link AmlSummaryReportShaper} this is NOT a
 * {@code PerRegulatorShaper} — the per-STR export is event-driven (fires
 * on {@code REVIEWED → FILED}) rather than periodic, and the input is a
 * single alert row, not a period compute. It sits between
 * {@link com.medfund.finance.regulatory.aml.service.AmlStrFilingService}
 * (which orchestrates alert lookup + XLSX render + MinIO upload) and the
 * cell map, staying pure so tests can drive it with a fixture alert.
 */
@Slf4j
@Component
public class AmlStrFilingShaper {

    public static final String TEMPLATE_KEY_ZW = "FIU_GOAML";
    public static final String TEMPLATE_KEY_ZA = "FIC";
    public static final String TEMPLATE_KEY_US = "FINCEN_SAR";

    /**
     * Compose the fill map from an alert. {@code tenantCountryCode} drives
     * {@code META_COUNTRY} + {@code META_TEMPLATE_KEY}; everything else
     * comes from the alert row directly. Null-safe on every optional field
     * so a not-yet-fully-worked alert (missing member/provider ids, blank
     * review note) still renders without NPE — the destination cell gets
     * an empty string.
     */
    public Map<AmlStrField, Object> compose(SuspiciousTransactionAlert alert,
                                            String reportingEntityName,
                                            String regulatorReference,
                                            String tenantCountryCode) {
        if (alert == null) {
            throw new IllegalArgumentException("alert required");
        }
        Map<AmlStrField, Object> out = new EnumMap<>(AmlStrField.class);

        String country = tenantCountryCode != null ? tenantCountryCode.toUpperCase(Locale.ROOT) : "";
        out.put(AmlStrField.META_REPORTING_ENTITY_NAME, orEmpty(reportingEntityName));
        out.put(AmlStrField.META_REGULATOR_REFERENCE, orEmpty(regulatorReference));
        out.put(AmlStrField.META_COUNTRY, country);
        out.put(AmlStrField.META_TEMPLATE_KEY, templateKeyFor(country));
        out.put(AmlStrField.META_GENERATED_AT, Instant.now().toString());

        out.put(AmlStrField.ALERT_ID, alert.getId() != null ? alert.getId().toString() : "");
        out.put(AmlStrField.ALERT_TRANSACTION_REF, orEmpty(alert.getTransactionRef()));
        out.put(AmlStrField.ALERT_TRANSACTION_TYPE, orEmpty(alert.getTransactionType()));
        out.put(AmlStrField.ALERT_AMOUNT, alert.getAmountNative());
        out.put(AmlStrField.ALERT_CURRENCY, orEmpty(alert.getCurrency()));
        out.put(AmlStrField.ALERT_MEMBER_ID,
                alert.getMemberId() != null ? alert.getMemberId().toString() : "");
        out.put(AmlStrField.ALERT_PROVIDER_ID,
                alert.getProviderId() != null ? alert.getProviderId().toString() : "");
        out.put(AmlStrField.ALERT_DESCRIPTION, orEmpty(alert.getDescription()));

        out.put(AmlStrField.ALERT_RAISED_AT,
                alert.getRaisedAt() != null ? alert.getRaisedAt().toString() : "");
        out.put(AmlStrField.ALERT_RAISED_BY_EMAIL, orEmpty(alert.getRaisedByActorEmail()));
        out.put(AmlStrField.ALERT_REVIEWED_AT,
                alert.getReviewedAt() != null ? alert.getReviewedAt().toString() : "");
        out.put(AmlStrField.ALERT_REVIEWER_EMAIL, orEmpty(alert.getReviewerActorEmail()));
        out.put(AmlStrField.ALERT_REVIEW_NOTE, orEmpty(alert.getReviewNote()));
        out.put(AmlStrField.ALERT_FILED_AT,
                alert.getFiledAt() != null ? alert.getFiledAt().toString() : "");
        out.put(AmlStrField.ALERT_FILED_BY_EMAIL, orEmpty(alert.getFilerActorEmail()));
        out.put(AmlStrField.ALERT_FILED_REF, orEmpty(alert.getFiledRef()));

        return out;
    }

    /**
     * Return the machine-readable template-key label for a country code.
     * Unknown codes default to {@link #TEMPLATE_KEY_ZA} + a WARN log so
     * the export still renders (matches {@link AmlXlsxService}'s fallback
     * posture for the periodic summary).
     */
    public static String templateKeyFor(String countryCode) {
        String c = countryCode != null ? countryCode.toUpperCase(Locale.ROOT) : "";
        return switch (c) {
            case "ZW" -> TEMPLATE_KEY_ZW;
            case "ZA" -> TEMPLATE_KEY_ZA;
            case "US" -> TEMPLATE_KEY_US;
            default -> {
                log.warn("[aml-str-shaper] unknown country '{}' — defaulting META_TEMPLATE_KEY to FIC",
                        countryCode);
                yield TEMPLATE_KEY_ZA;
            }
        };
    }

    private static String orEmpty(String s) {
        return s != null ? s : "";
    }
}
