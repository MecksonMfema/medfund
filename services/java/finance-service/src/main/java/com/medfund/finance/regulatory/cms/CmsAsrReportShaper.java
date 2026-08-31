package com.medfund.finance.regulatory.cms;

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
import java.util.Map;
import java.util.UUID;

/**
 * Shapes the CMS Annual Statutory Return for a ({@code tenantId}, year)
 * tuple. Delegates raw data assembly to {@link CmsAsrRawDataProvider};
 * runs the solvency + cost-ratio blocks through {@link CmsAsrCalculator}
 * using the YAML defaults resolved for the period end; assembles the
 * result into a {@link RegulatoryReportData} whose section keys line up
 * with what the XLSX writer + {@link CmsCellMap} consume.
 *
 * <p>Reporting currency is fixed at ZAR per
 * {@link RegulatoryReportCurrency#fixedFor(ReportKey)} — the currency-override
 * rejection lives on the controller through
 * {@link com.medfund.finance.regulatory.service.RegulatoryReportShapingService#rejectClientCurrencyOverride}.
 */
@Slf4j
@Component
public class CmsAsrReportShaper implements PerRegulatorShaper {

    public static final String SECTION_META = "meta";
    public static final String SECTION_MEMBERSHIP = "membership";
    public static final String SECTION_BALANCE_SHEET = "balance_sheet";
    public static final String SECTION_INCOME_STATEMENT = "income_statement";
    public static final String SECTION_COST_RATIOS = "cost_ratios";
    public static final String SECTION_SOLVENCY = "solvency";

    private final CmsAsrRawDataProvider rawDataProvider;
    private final CmsAsrCalculator calculator;
    /** Phase 15b — nullable so unit tests can construct without the rules-engine wiring. */
    private final @Nullable RegulatoryParameterResolver parameterResolver;

    /** Spring wires this constructor — {@code RegulatoryParameterResolver} is a required bean. */
    @Autowired
    public CmsAsrReportShaper(CmsAsrRawDataProvider rawDataProvider,
                              CmsAsrCalculator calculator,
                              RegulatoryParameterResolver parameterResolver) {
        this.rawDataProvider = rawDataProvider;
        this.calculator = calculator;
        this.parameterResolver = parameterResolver;
    }

    /** Test constructor — YAML-only fallback (no resolver = bundled defaults win). */
    public CmsAsrReportShaper(CmsAsrRawDataProvider rawDataProvider,
                              CmsAsrCalculator calculator) {
        this(rawDataProvider, calculator, null);
    }

    @Override
    public ReportKey supportedKey() {
        return ReportKey.CMS_ASR;
    }

    @Override
    public Mono<RegulatoryReportData> shape(UUID tenantId,
                                            LocalDate periodStart,
                                            LocalDate periodEnd,
                                            String tenantCountryCode) {
        String currency = RegulatoryReportCurrency.resolveOrThrow(
                ReportKey.CMS_ASR, tenantCountryCode);
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
     * {@link #compose(CmsAsrRawData, CmsSolvencyParameters, UUID, LocalDate, LocalDate, String)}
     * overload which takes pre-resolved parameters.
     */
    RegulatoryReportData compose(CmsAsrRawData raw,
                                 UUID tenantId,
                                 LocalDate periodStart,
                                 LocalDate periodEnd,
                                 String currency) {
        return compose(raw, calculator.resolveParameters(periodEnd),
                tenantId, periodStart, periodEnd, currency);
    }

    /** Primary compose seam — takes pre-resolved parameters (rules-engine + YAML fallback). */
    RegulatoryReportData compose(CmsAsrRawData raw,
                                 CmsSolvencyParameters params,
                                 UUID tenantId,
                                 LocalDate periodStart,
                                 LocalDate periodEnd,
                                 String currency) {
        CmsAsrCalculator.SolvencyResult solvency = calculator.computeSolvency(
                raw.totalAssets(),
                raw.totalLiabilities(),
                raw.grossContributions(),
                params);
        CmsAsrCalculator.CostRatios ratios = calculator.computeCostRatios(
                raw.grossContributions(),
                raw.riskClaimsIncurred(),
                raw.adminExpenses(),
                raw.brokerFees(),
                raw.managedCareFees());

        Map<CmsField, Object> flat = new EnumMap<>(CmsField.class);
        // Meta
        flat.put(CmsField.META_SCHEME_NAME, raw.schemeName());
        flat.put(CmsField.META_REGISTRATION_NUMBER, raw.registrationNumber());
        flat.put(CmsField.META_REPORTING_CURRENCY, currency);
        flat.put(CmsField.META_PERIOD_START, periodStart);
        flat.put(CmsField.META_PERIOD_END, periodEnd);
        // Membership
        flat.put(CmsField.MEM_PRINCIPAL_MEMBERS, raw.principalMembers());
        flat.put(CmsField.MEM_DEPENDANTS, raw.dependants());
        flat.put(CmsField.MEM_TOTAL_BENEFICIARIES, raw.totalBeneficiaries());
        flat.put(CmsField.MEM_PENSIONER_RATIO, raw.pensionerRatio());
        // Balance sheet
        flat.put(CmsField.BS_TOTAL_ASSETS, raw.totalAssets());
        flat.put(CmsField.BS_TOTAL_LIABILITIES, raw.totalLiabilities());
        flat.put(CmsField.BS_ACCUMULATED_FUNDS, solvency.accumulatedFunds());
        // Income statement
        flat.put(CmsField.INC_GROSS_CONTRIBUTIONS, raw.grossContributions());
        flat.put(CmsField.INC_NET_CONTRIBUTIONS, raw.netContributions());
        flat.put(CmsField.INC_RISK_CLAIMS_INCURRED, raw.riskClaimsIncurred());
        flat.put(CmsField.INC_ADMIN_EXPENSES, raw.adminExpenses());
        flat.put(CmsField.INC_BROKER_FEES, raw.brokerFees());
        flat.put(CmsField.INC_MANAGED_CARE_FEES, raw.managedCareFees());
        flat.put(CmsField.INC_NON_HEALTHCARE_TOTAL, raw.nonHealthcareTotal());
        flat.put(CmsField.INC_NET_SURPLUS, raw.netSurplus());
        // Cost ratios
        flat.put(CmsField.RATIO_CLAIMS, ratios.claimsRatio());
        flat.put(CmsField.RATIO_NON_HEALTHCARE, ratios.nonHealthcareRatio());
        flat.put(CmsField.RATIO_ADMIN, ratios.adminRatio());
        flat.put(CmsField.RATIO_BROKER, ratios.brokerRatio());
        flat.put(CmsField.RATIO_MANAGED_CARE, ratios.managedCareRatio());
        // Solvency
        flat.put(CmsField.SOL_ACCUMULATED_FUNDS, solvency.accumulatedFunds());
        flat.put(CmsField.SOL_MIN_REQUIRED_RESERVES, solvency.minRequiredReserves());
        flat.put(CmsField.SOL_ACTUAL_RATIO, solvency.actualRatio());
        flat.put(CmsField.SOL_MIN_REQUIRED_RATIO, solvency.minRequiredRatio());
        flat.put(CmsField.SOL_SURPLUS_DEFICIT, solvency.surplusDeficit());

        return RegulatoryReportData.builder(
                        ReportKey.CMS_ASR, tenantId, periodStart, periodEnd, currency)
                .section(SECTION_META, subMap(flat,
                        CmsField.META_SCHEME_NAME, CmsField.META_REGISTRATION_NUMBER,
                        CmsField.META_REPORTING_CURRENCY,
                        CmsField.META_PERIOD_START, CmsField.META_PERIOD_END))
                .section(SECTION_MEMBERSHIP, subMap(flat,
                        CmsField.MEM_PRINCIPAL_MEMBERS, CmsField.MEM_DEPENDANTS,
                        CmsField.MEM_TOTAL_BENEFICIARIES, CmsField.MEM_PENSIONER_RATIO))
                .section(SECTION_BALANCE_SHEET, subMap(flat,
                        CmsField.BS_TOTAL_ASSETS, CmsField.BS_TOTAL_LIABILITIES,
                        CmsField.BS_ACCUMULATED_FUNDS))
                .section(SECTION_INCOME_STATEMENT, subMap(flat,
                        CmsField.INC_GROSS_CONTRIBUTIONS, CmsField.INC_NET_CONTRIBUTIONS,
                        CmsField.INC_RISK_CLAIMS_INCURRED, CmsField.INC_ADMIN_EXPENSES,
                        CmsField.INC_BROKER_FEES, CmsField.INC_MANAGED_CARE_FEES,
                        CmsField.INC_NON_HEALTHCARE_TOTAL, CmsField.INC_NET_SURPLUS))
                .section(SECTION_COST_RATIOS, subMap(flat,
                        CmsField.RATIO_CLAIMS, CmsField.RATIO_NON_HEALTHCARE,
                        CmsField.RATIO_ADMIN, CmsField.RATIO_BROKER,
                        CmsField.RATIO_MANAGED_CARE))
                .section(SECTION_SOLVENCY, subMap(flat,
                        CmsField.SOL_ACCUMULATED_FUNDS, CmsField.SOL_MIN_REQUIRED_RESERVES,
                        CmsField.SOL_ACTUAL_RATIO, CmsField.SOL_MIN_REQUIRED_RATIO,
                        CmsField.SOL_SURPLUS_DEFICIT))
                .build();
    }

    /** Extract the flat field/value map for a single section, preserving order. */
    private static Map<String, Object> subMap(Map<CmsField, Object> flat, CmsField... keys) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (CmsField k : keys) {
            out.put(k.name(), flat.get(k));
        }
        return out;
    }

    /**
     * Rebuild a {@code Map<CmsField, Object>} from the composed sections
     * — used by the XLSX writer to fill the template via the cell map.
     */
    public static Map<CmsField, Object> toCellValueMap(RegulatoryReportData data) {
        Map<CmsField, Object> out = new EnumMap<>(CmsField.class);
        for (Map<String, Object> section : data.sections().values()) {
            for (Map.Entry<String, Object> e : section.entrySet()) {
                try {
                    CmsField key = CmsField.valueOf(e.getKey());
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
