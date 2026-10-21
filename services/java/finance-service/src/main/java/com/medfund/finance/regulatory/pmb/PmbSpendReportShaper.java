package com.medfund.finance.regulatory.pmb;

import com.medfund.finance.regulatory.service.PerRegulatorShaper;
import com.medfund.finance.regulatory.service.RegulatoryReportData;
import com.medfund.shared.report.ReportKey;
import com.medfund.shared.report.regulatory.RegulatoryReportCurrency;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Shapes the PMB Spend report for a ({@code tenantId}, period) tuple.
 * Delegates raw aggregation to {@link PmbSpendRawDataProvider}; derives the
 * summary ratios via {@link PmbSpendCalculator}; assembles the result into
 * a {@link RegulatoryReportData} whose section keys line up with what the
 * XLSX writer + {@link PmbCellMap} consume.
 *
 * <p>Reporting currency is fixed at ZAR per
 * {@link RegulatoryReportCurrency#fixedFor(ReportKey)} — the currency-override
 * rejection lives on the controller through
 * {@link com.medfund.finance.regulatory.service.RegulatoryReportShapingService#rejectClientCurrencyOverride}.
 *
 * <p>Unlike IPEC / CMS / NAIC there is no {@code RegulatoryParameterResolver}
 * plumbing — PMB spend has no configurable statutory parameters (the report
 * is a straight aggregation).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PmbSpendReportShaper implements PerRegulatorShaper {

    public static final String SECTION_META = "meta";
    public static final String SECTION_MEMBERSHIP = "membership";
    public static final String SECTION_CATEGORY_PAID = "category_paid";
    public static final String SECTION_CATEGORY_COUNT = "category_count";
    public static final String SECTION_TOTALS = "totals";

    private final PmbSpendRawDataProvider rawDataProvider;
    private final PmbSpendCalculator calculator;

    @Override
    public ReportKey supportedKey() {
        return ReportKey.PMB_SPEND;
    }

    @Override
    public Mono<RegulatoryReportData> shape(UUID tenantId,
                                            LocalDate periodStart,
                                            LocalDate periodEnd,
                                            String tenantCountryCode) {
        String currency = RegulatoryReportCurrency.resolveOrThrow(
                ReportKey.PMB_SPEND, tenantCountryCode);
        return rawDataProvider.load(tenantId, periodStart, periodEnd)
                .map(raw -> compose(raw, tenantId, periodStart, periodEnd, currency));
    }

    /**
     * Package-private compose seam — tests drive raw data directly rather
     * than mocking the provider. Public callers go through {@link #shape}.
     */
    RegulatoryReportData compose(PmbSpendRawData raw,
                                 UUID tenantId,
                                 LocalDate periodStart,
                                 LocalDate periodEnd,
                                 String currency) {
        PmbSpendCalculator.Summary summary = calculator.computeSummary(
                raw.totalPmbPaid(),
                raw.nonPmbPaid(),
                raw.totalBeneficiaries());

        Map<PmbField, Object> flat = new EnumMap<>(PmbField.class);
        // Meta
        flat.put(PmbField.META_SCHEME_NAME, raw.schemeName());
        flat.put(PmbField.META_REGISTRATION_NUMBER, raw.registrationNumber());
        flat.put(PmbField.META_REPORTING_CURRENCY, currency);
        flat.put(PmbField.META_PERIOD_START, periodStart);
        flat.put(PmbField.META_PERIOD_END, periodEnd);
        // Membership
        flat.put(PmbField.MEM_TOTAL_BENEFICIARIES, raw.totalBeneficiaries());
        // Per-category paid
        flat.put(PmbField.CAT_RESPIRATORY_PAID,   paid(raw, PmbCategory.RESPIRATORY));
        flat.put(PmbField.CAT_CARDIAC_PAID,       paid(raw, PmbCategory.CARDIAC));
        flat.put(PmbField.CAT_METABOLIC_PAID,     paid(raw, PmbCategory.METABOLIC));
        flat.put(PmbField.CAT_ONCOLOGY_PAID,      paid(raw, PmbCategory.ONCOLOGY));
        flat.put(PmbField.CAT_MENTAL_HEALTH_PAID, paid(raw, PmbCategory.MENTAL_HEALTH));
        flat.put(PmbField.CAT_RENAL_PAID,         paid(raw, PmbCategory.RENAL));
        flat.put(PmbField.CAT_OTHER_PAID,         paid(raw, PmbCategory.OTHER));
        // Per-category count
        flat.put(PmbField.CAT_RESPIRATORY_COUNT,   count(raw, PmbCategory.RESPIRATORY));
        flat.put(PmbField.CAT_CARDIAC_COUNT,       count(raw, PmbCategory.CARDIAC));
        flat.put(PmbField.CAT_METABOLIC_COUNT,     count(raw, PmbCategory.METABOLIC));
        flat.put(PmbField.CAT_ONCOLOGY_COUNT,      count(raw, PmbCategory.ONCOLOGY));
        flat.put(PmbField.CAT_MENTAL_HEALTH_COUNT, count(raw, PmbCategory.MENTAL_HEALTH));
        flat.put(PmbField.CAT_RENAL_COUNT,         count(raw, PmbCategory.RENAL));
        flat.put(PmbField.CAT_OTHER_COUNT,         count(raw, PmbCategory.OTHER));
        // Totals
        flat.put(PmbField.TOTAL_PMB_PAID, summary.totalPmbPaid());
        flat.put(PmbField.TOTAL_PMB_COUNT, raw.totalPmbCount());
        flat.put(PmbField.TOTAL_NON_PMB_PAID, summary.totalNonPmbPaid());
        flat.put(PmbField.TOTAL_ALL_CLAIMS_PAID, summary.totalAllClaimsPaid());
        flat.put(PmbField.TOTAL_PMB_RATIO, summary.pmbRatio());
        flat.put(PmbField.TOTAL_PMB_PAID_PER_BENEFICIARY, summary.pmbPaidPerBeneficiary());

        return RegulatoryReportData.builder(
                        ReportKey.PMB_SPEND, tenantId, periodStart, periodEnd, currency)
                .section(SECTION_META, subMap(flat,
                        PmbField.META_SCHEME_NAME, PmbField.META_REGISTRATION_NUMBER,
                        PmbField.META_REPORTING_CURRENCY,
                        PmbField.META_PERIOD_START, PmbField.META_PERIOD_END))
                .section(SECTION_MEMBERSHIP, subMap(flat,
                        PmbField.MEM_TOTAL_BENEFICIARIES))
                .section(SECTION_CATEGORY_PAID, subMap(flat,
                        PmbField.CAT_RESPIRATORY_PAID, PmbField.CAT_CARDIAC_PAID,
                        PmbField.CAT_METABOLIC_PAID, PmbField.CAT_ONCOLOGY_PAID,
                        PmbField.CAT_MENTAL_HEALTH_PAID, PmbField.CAT_RENAL_PAID,
                        PmbField.CAT_OTHER_PAID))
                .section(SECTION_CATEGORY_COUNT, subMap(flat,
                        PmbField.CAT_RESPIRATORY_COUNT, PmbField.CAT_CARDIAC_COUNT,
                        PmbField.CAT_METABOLIC_COUNT, PmbField.CAT_ONCOLOGY_COUNT,
                        PmbField.CAT_MENTAL_HEALTH_COUNT, PmbField.CAT_RENAL_COUNT,
                        PmbField.CAT_OTHER_COUNT))
                .section(SECTION_TOTALS, subMap(flat,
                        PmbField.TOTAL_PMB_PAID, PmbField.TOTAL_PMB_COUNT,
                        PmbField.TOTAL_NON_PMB_PAID, PmbField.TOTAL_ALL_CLAIMS_PAID,
                        PmbField.TOTAL_PMB_RATIO, PmbField.TOTAL_PMB_PAID_PER_BENEFICIARY))
                .build();
    }

    /** Extract the flat field/value map for a single section, preserving order. */
    private static Map<String, Object> subMap(Map<PmbField, Object> flat, PmbField... keys) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (PmbField k : keys) {
            out.put(k.name(), flat.get(k));
        }
        return out;
    }

    /**
     * Rebuild a {@code Map<PmbField, Object>} from the composed sections
     * — used by the XLSX writer to fill the template via the cell map.
     */
    public static Map<PmbField, Object> toCellValueMap(RegulatoryReportData data) {
        Map<PmbField, Object> out = new EnumMap<>(PmbField.class);
        for (Map<String, Object> section : data.sections().values()) {
            for (Map.Entry<String, Object> e : section.entrySet()) {
                try {
                    PmbField key = PmbField.valueOf(e.getKey());
                    out.put(key, e.getValue());
                } catch (IllegalArgumentException ignored) {
                    // Foreign section entries are tolerated — the shaper is the sole
                    // populator today so this can't fire in practice.
                }
            }
        }
        return out;
    }

    private static BigDecimal paid(PmbSpendRawData raw, PmbCategory cat) {
        BigDecimal v = raw.pmbPaidByCategory().get(cat);
        return v != null ? v : BigDecimal.ZERO;
    }

    private static long count(PmbSpendRawData raw, PmbCategory cat) {
        Long v = raw.pmbCountByCategory().get(cat);
        return v != null ? v : 0L;
    }
}
