package com.medfund.finance.regulatory.naic;

import com.medfund.finance.regulatory.service.PerRegulatorShaper;
import com.medfund.finance.regulatory.service.RegulatoryParameterResolver;
import com.medfund.finance.regulatory.service.RegulatoryReportData;
import com.medfund.shared.report.ReportKey;
import com.medfund.shared.report.regulatory.RegulatoryReportCurrency;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Shapes the NAIC Schedule P for a ({@code tenantId}, year) tuple.
 * Delegates raw data assembly to {@link NaicSchedulePRawDataProvider};
 * runs each accident year through {@link NaicSchedulePCalculator} using
 * the YAML defaults resolved for the period end; assembles the result
 * into a {@link RegulatoryReportData} whose section keys line up with
 * what the XLSX writer + {@link NaicPCellMap} consume.
 *
 * <p>Reporting currency is fixed at USD per
 * {@link RegulatoryReportCurrency#fixedFor(ReportKey)} — the currency-override
 * rejection lives on the controller through
 * {@link com.medfund.finance.regulatory.service.RegulatoryReportShapingService#rejectClientCurrencyOverride}.
 */
@Slf4j
@Component
public class NaicSchedulePReportShaper implements PerRegulatorShaper {

    public static final String SECTION_META = "meta";
    public static final String SECTION_INCURRED = "incurred";
    public static final String SECTION_PAID = "paid";
    public static final String SECTION_CASE = "case_reserves";
    public static final String SECTION_IBNR = "ibnr";
    public static final String SECTION_EARNED_PREMIUM = "earned_premium";
    public static final String SECTION_LOSS_RATIOS = "loss_ratios";
    public static final String SECTION_TOTALS = "totals";

    private final NaicSchedulePRawDataProvider rawDataProvider;
    private final NaicSchedulePCalculator calculator;
    /** Phase 15b — nullable so unit tests can construct without the rules-engine wiring. */
    private final @Nullable RegulatoryParameterResolver parameterResolver;

    /** Spring wires this constructor — {@code RegulatoryParameterResolver} is a required bean. */
    @Autowired
    public NaicSchedulePReportShaper(NaicSchedulePRawDataProvider rawDataProvider,
                                     NaicSchedulePCalculator calculator,
                                     RegulatoryParameterResolver parameterResolver) {
        this.rawDataProvider = rawDataProvider;
        this.calculator = calculator;
        this.parameterResolver = parameterResolver;
    }

    /** Test constructor — YAML-only fallback (no resolver = bundled defaults win). */
    public NaicSchedulePReportShaper(NaicSchedulePRawDataProvider rawDataProvider,
                                     NaicSchedulePCalculator calculator) {
        this(rawDataProvider, calculator, null);
    }

    @Override
    public ReportKey supportedKey() {
        return ReportKey.NAIC_SCHEDULE_P;
    }

    @Override
    public Mono<RegulatoryReportData> shape(UUID tenantId,
                                            LocalDate periodStart,
                                            LocalDate periodEnd,
                                            String tenantCountryCode) {
        String currency = RegulatoryReportCurrency.resolveOrThrow(
                ReportKey.NAIC_SCHEDULE_P, tenantCountryCode);
        if (parameterResolver == null) {
            return rawDataProvider.load(tenantId, periodStart, periodEnd)
                    .map(raw -> compose(raw, tenantId, periodStart, periodEnd, currency));
        }
        return Mono.zip(
                        rawDataProvider.load(tenantId, periodStart, periodEnd),
                        calculator.resolveParameters(parameterResolver, tenantId, periodEnd))
                .map(t -> compose(t.getT1(), t.getT2(), tenantId, periodStart, periodEnd, currency));
    }

    /**
     * Package-private compose seam — tests drive raw data directly rather than
     * mocking the provider. Public callers go through {@link #shape}. This
     * YAML-only overload keeps every existing golden-fixture test call site
     * unchanged; the resolver-based path goes through the
     * {@link #compose(NaicSchedulePRawData, NaicSolvencyParameters, UUID, LocalDate, LocalDate, String)}
     * overload which takes pre-resolved parameters.
     */
    RegulatoryReportData compose(NaicSchedulePRawData raw,
                                 UUID tenantId,
                                 LocalDate periodStart,
                                 LocalDate periodEnd,
                                 String currency) {
        return compose(raw, calculator.resolveParameters(periodEnd),
                tenantId, periodStart, periodEnd, currency);
    }

    /**
     * Primary compose seam — takes pre-resolved parameters (rules-engine +
     * YAML fallback). The parameters themselves are unused in the compute
     * chain today but are still resolved so a broken YAML or rule surfaces
     * on the shape path rather than silently on a downstream ULAE-only export.
     */
    RegulatoryReportData compose(NaicSchedulePRawData raw,
                                 NaicSolvencyParameters params,
                                 UUID tenantId,
                                 LocalDate periodStart,
                                 LocalDate periodEnd,
                                 String currency) {
        NaicSchedulePCalculator.AccidentYearResult ayMinus2 = calculator.computeAccidentYear(
                raw.paidAyMinus2(), raw.caseAyMinus2(), raw.ibnrAyMinus2(), raw.earnedPremiumAyMinus2());
        NaicSchedulePCalculator.AccidentYearResult ayMinus1 = calculator.computeAccidentYear(
                raw.paidAyMinus1(), raw.caseAyMinus1(), raw.ibnrAyMinus1(), raw.earnedPremiumAyMinus1());
        NaicSchedulePCalculator.AccidentYearResult ayCurrent = calculator.computeAccidentYear(
                raw.paidAyCurrent(), raw.caseAyCurrent(), raw.ibnrAyCurrent(), raw.earnedPremiumAyCurrent());
        NaicSchedulePCalculator.TotalsResult totals = calculator.computeTotals(
                List.of(ayMinus2, ayMinus1, ayCurrent));

        Map<NaicPField, Object> flat = new EnumMap<>(NaicPField.class);
        // Meta
        flat.put(NaicPField.META_COMPANY_NAME, raw.companyName());
        flat.put(NaicPField.META_NAIC_CODE, raw.naicCode());
        flat.put(NaicPField.META_GROUP_CODE, raw.groupCode());
        flat.put(NaicPField.META_FEIN, raw.fein());
        flat.put(NaicPField.META_STATE, raw.stateOfDomicile());
        flat.put(NaicPField.META_REPORTING_CURRENCY, currency);
        flat.put(NaicPField.META_PERIOD_START, periodStart);
        flat.put(NaicPField.META_PERIOD_END, periodEnd);
        // Part 1 — Incurred
        flat.put(NaicPField.P1_INCURRED_AY_MINUS_2, ayMinus2.incurred());
        flat.put(NaicPField.P1_INCURRED_AY_MINUS_1, ayMinus1.incurred());
        flat.put(NaicPField.P1_INCURRED_AY_CURRENT, ayCurrent.incurred());
        // Part 2 — Paid
        flat.put(NaicPField.P2_PAID_AY_MINUS_2, ayMinus2.paid());
        flat.put(NaicPField.P2_PAID_AY_MINUS_1, ayMinus1.paid());
        flat.put(NaicPField.P2_PAID_AY_CURRENT, ayCurrent.paid());
        // Part 3 — Case reserves
        flat.put(NaicPField.P3_CASE_AY_MINUS_2, ayMinus2.caseReserves());
        flat.put(NaicPField.P3_CASE_AY_MINUS_1, ayMinus1.caseReserves());
        flat.put(NaicPField.P3_CASE_AY_CURRENT, ayCurrent.caseReserves());
        // Part 4 — IBNR
        flat.put(NaicPField.P4_IBNR_AY_MINUS_2, ayMinus2.ibnr());
        flat.put(NaicPField.P4_IBNR_AY_MINUS_1, ayMinus1.ibnr());
        flat.put(NaicPField.P4_IBNR_AY_CURRENT, ayCurrent.ibnr());
        // Part 5 — Earned premium
        flat.put(NaicPField.P5_EARNED_PREMIUM_AY_MINUS_2, ayMinus2.earnedPremium());
        flat.put(NaicPField.P5_EARNED_PREMIUM_AY_MINUS_1, ayMinus1.earnedPremium());
        flat.put(NaicPField.P5_EARNED_PREMIUM_AY_CURRENT, ayCurrent.earnedPremium());
        // Part 6 — Loss ratios
        flat.put(NaicPField.P6_LOSS_RATIO_AY_MINUS_2, ayMinus2.lossRatio());
        flat.put(NaicPField.P6_LOSS_RATIO_AY_MINUS_1, ayMinus1.lossRatio());
        flat.put(NaicPField.P6_LOSS_RATIO_AY_CURRENT, ayCurrent.lossRatio());
        // Totals
        flat.put(NaicPField.TOTAL_INCURRED, totals.totalIncurred());
        flat.put(NaicPField.TOTAL_PAID, totals.totalPaid());
        flat.put(NaicPField.TOTAL_CASE_RESERVES, totals.totalCaseReserves());
        flat.put(NaicPField.TOTAL_IBNR, totals.totalIbnr());
        flat.put(NaicPField.TOTAL_EARNED_PREMIUM, totals.totalEarnedPremium());
        flat.put(NaicPField.OVERALL_LOSS_RATIO, totals.overallLossRatio());

        return RegulatoryReportData.builder(
                        ReportKey.NAIC_SCHEDULE_P, tenantId, periodStart, periodEnd, currency)
                .section(SECTION_META, subMap(flat,
                        NaicPField.META_COMPANY_NAME, NaicPField.META_NAIC_CODE,
                        NaicPField.META_GROUP_CODE, NaicPField.META_FEIN,
                        NaicPField.META_STATE, NaicPField.META_REPORTING_CURRENCY,
                        NaicPField.META_PERIOD_START, NaicPField.META_PERIOD_END))
                .section(SECTION_INCURRED, subMap(flat,
                        NaicPField.P1_INCURRED_AY_MINUS_2,
                        NaicPField.P1_INCURRED_AY_MINUS_1,
                        NaicPField.P1_INCURRED_AY_CURRENT))
                .section(SECTION_PAID, subMap(flat,
                        NaicPField.P2_PAID_AY_MINUS_2,
                        NaicPField.P2_PAID_AY_MINUS_1,
                        NaicPField.P2_PAID_AY_CURRENT))
                .section(SECTION_CASE, subMap(flat,
                        NaicPField.P3_CASE_AY_MINUS_2,
                        NaicPField.P3_CASE_AY_MINUS_1,
                        NaicPField.P3_CASE_AY_CURRENT))
                .section(SECTION_IBNR, subMap(flat,
                        NaicPField.P4_IBNR_AY_MINUS_2,
                        NaicPField.P4_IBNR_AY_MINUS_1,
                        NaicPField.P4_IBNR_AY_CURRENT))
                .section(SECTION_EARNED_PREMIUM, subMap(flat,
                        NaicPField.P5_EARNED_PREMIUM_AY_MINUS_2,
                        NaicPField.P5_EARNED_PREMIUM_AY_MINUS_1,
                        NaicPField.P5_EARNED_PREMIUM_AY_CURRENT))
                .section(SECTION_LOSS_RATIOS, subMap(flat,
                        NaicPField.P6_LOSS_RATIO_AY_MINUS_2,
                        NaicPField.P6_LOSS_RATIO_AY_MINUS_1,
                        NaicPField.P6_LOSS_RATIO_AY_CURRENT))
                .section(SECTION_TOTALS, subMap(flat,
                        NaicPField.TOTAL_INCURRED, NaicPField.TOTAL_PAID,
                        NaicPField.TOTAL_CASE_RESERVES, NaicPField.TOTAL_IBNR,
                        NaicPField.TOTAL_EARNED_PREMIUM, NaicPField.OVERALL_LOSS_RATIO))
                .build();
    }

    /** Extract the flat field/value map for a single section, preserving order. */
    private static Map<String, Object> subMap(Map<NaicPField, Object> flat, NaicPField... keys) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (NaicPField k : keys) {
            out.put(k.name(), flat.get(k));
        }
        return out;
    }

    /**
     * Rebuild a {@code Map<NaicPField, Object>} from the composed sections
     * — used by the XLSX writer to fill the template via the cell map.
     */
    public static Map<NaicPField, Object> toCellValueMap(RegulatoryReportData data) {
        Map<NaicPField, Object> out = new EnumMap<>(NaicPField.class);
        for (Map<String, Object> section : data.sections().values()) {
            for (Map.Entry<String, Object> e : section.entrySet()) {
                try {
                    NaicPField key = NaicPField.valueOf(e.getKey());
                    out.put(key, e.getValue());
                } catch (IllegalArgumentException ignored) {
                    // Foreign section entries are tolerated — the shaper is the sole
                    // populator today so this can't fire in practice.
                }
            }
        }
        return out;
    }
}
