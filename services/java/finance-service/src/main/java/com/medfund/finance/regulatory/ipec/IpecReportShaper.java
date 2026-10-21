package com.medfund.finance.regulatory.ipec;

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
 * Shapes the IPEC quarterly return for a ({@code tenantId}, quarter) tuple.
 * Delegates raw data assembly to {@link IpecRawDataProvider}; runs the
 * solvency block through {@link IpecSolvencyCalculator} using the YAML
 * defaults resolved for the period end; assembles the result into a
 * {@link RegulatoryReportData} whose section keys line up with what the
 * XLSX writer + {@link IpecCellMap} consume.
 *
 * <p>Reporting currency is fixed at ZWL per
 * {@link RegulatoryReportCurrency#fixedFor(ReportKey)} — the currency-override
 * rejection lives on the controller through
 * {@link com.medfund.finance.regulatory.service.RegulatoryReportShapingService#rejectClientCurrencyOverride}.
 */
@Slf4j
@Component
public class IpecReportShaper implements PerRegulatorShaper {

    public static final String SECTION_META = "meta";
    public static final String SECTION_BALANCE_SHEET = "balance_sheet";
    public static final String SECTION_REVENUE_ACCOUNT = "revenue_account";
    public static final String SECTION_UPR = "upr";
    public static final String SECTION_OSC = "osc";
    public static final String SECTION_IBNR = "ibnr";
    public static final String SECTION_REINSURANCE = "reinsurance_recoverables";
    public static final String SECTION_SOLVENCY = "solvency";

    private final IpecRawDataProvider rawDataProvider;
    private final IpecSolvencyCalculator solvencyCalculator;
    /** Phase 15b — nullable so unit tests can construct without the rules-engine wiring. */
    private final @Nullable RegulatoryParameterResolver parameterResolver;

    /** Spring wires this constructor — {@code RegulatoryParameterResolver} is a required bean. */
    @Autowired
    public IpecReportShaper(IpecRawDataProvider rawDataProvider,
                            IpecSolvencyCalculator solvencyCalculator,
                            RegulatoryParameterResolver parameterResolver) {
        this.rawDataProvider = rawDataProvider;
        this.solvencyCalculator = solvencyCalculator;
        this.parameterResolver = parameterResolver;
    }

    /** Test constructor — YAML-only fallback (no resolver = bundled defaults win). */
    public IpecReportShaper(IpecRawDataProvider rawDataProvider,
                            IpecSolvencyCalculator solvencyCalculator) {
        this(rawDataProvider, solvencyCalculator, null);
    }

    @Override
    public ReportKey supportedKey() {
        return ReportKey.IPEC_QUARTERLY_RETURN;
    }

    @Override
    public Mono<RegulatoryReportData> shape(UUID tenantId,
                                            LocalDate periodStart,
                                            LocalDate periodEnd,
                                            String tenantCountryCode) {
        String currency = RegulatoryReportCurrency.resolveOrThrow(
                ReportKey.IPEC_QUARTERLY_RETURN, tenantCountryCode);
        if (parameterResolver == null) {
            return rawDataProvider.load(tenantId, periodStart, periodEnd)
                    .map(raw -> compose(raw, tenantId, periodStart, periodEnd, currency));
        }
        return Mono.zip(
                        rawDataProvider.load(tenantId, periodStart, periodEnd),
                        solvencyCalculator.resolveParameters(parameterResolver, tenantId, periodEnd))
                .map(t -> compose(t.getT1(), t.getT2(), tenantId, periodStart, periodEnd, currency));
    }

    /**
     * Package-private compose seam — tests drive raw data directly rather than
     * mocking the provider. Public callers go through {@link #shape}. This
     * YAML-only overload keeps every existing golden-fixture test call site
     * unchanged; the resolver-based path goes through the
     * {@link #compose(IpecRawData, IpecSolvencyParameters, UUID, LocalDate, LocalDate, String)}
     * overload which takes pre-resolved parameters.
     */
    RegulatoryReportData compose(IpecRawData raw,
                                 UUID tenantId,
                                 LocalDate periodStart,
                                 LocalDate periodEnd,
                                 String currency) {
        return compose(raw, solvencyCalculator.resolveParameters(periodEnd),
                tenantId, periodStart, periodEnd, currency);
    }

    /** Primary compose seam — takes pre-resolved parameters (rules-engine + YAML fallback). */
    RegulatoryReportData compose(IpecRawData raw,
                                 IpecSolvencyParameters params,
                                 UUID tenantId,
                                 LocalDate periodStart,
                                 LocalDate periodEnd,
                                 String currency) {
        IpecSolvencyCalculator.SolvencyResult solvency = solvencyCalculator.compute(
                raw.totalAssets(),
                raw.totalLiabilities(),
                raw.netEarnedPremium(),
                sum(raw.oscHealth(), raw.oscMotor(), raw.oscProperty()),
                sum(raw.ibnrHealth(), raw.ibnrMotor(), raw.ibnrProperty()),
                params);

        Map<IpecField, Object> flat = new EnumMap<>(IpecField.class);
        // Meta
        flat.put(IpecField.META_TENANT_NAME, raw.tenantName());
        flat.put(IpecField.META_LICENCE_NUMBER, raw.licenceNumber());
        flat.put(IpecField.META_REPORTING_CURRENCY, currency);
        flat.put(IpecField.META_PERIOD_START, periodStart);
        flat.put(IpecField.META_PERIOD_END, periodEnd);
        // Balance sheet
        flat.put(IpecField.BS_TOTAL_ASSETS, raw.totalAssets());
        flat.put(IpecField.BS_TOTAL_LIABILITIES, raw.totalLiabilities());
        flat.put(IpecField.BS_TOTAL_EQUITY, raw.totalAssets().subtract(raw.totalLiabilities()));
        // Revenue account
        flat.put(IpecField.REV_GWP_HEALTH, raw.gwpHealth());
        flat.put(IpecField.REV_GWP_MOTOR, raw.gwpMotor());
        flat.put(IpecField.REV_GWP_PROPERTY, raw.gwpProperty());
        flat.put(IpecField.REV_CEDED_REINSURANCE, raw.cededReinsurancePremium());
        flat.put(IpecField.REV_NET_EARNED_PREMIUM, raw.netEarnedPremium());
        flat.put(IpecField.REV_NET_CLAIMS_INCURRED, raw.netClaimsIncurred());
        flat.put(IpecField.REV_MANAGEMENT_EXPENSES, raw.managementExpenses());
        flat.put(IpecField.REV_UNDERWRITING_RESULT,
                raw.netEarnedPremium().subtract(raw.netClaimsIncurred()).subtract(raw.managementExpenses()));
        // UPR / OSC / IBNR
        flat.put(IpecField.UPR_HEALTH, raw.uprHealth());
        flat.put(IpecField.UPR_MOTOR, raw.uprMotor());
        flat.put(IpecField.UPR_PROPERTY, raw.uprProperty());
        flat.put(IpecField.OSC_HEALTH, raw.oscHealth());
        flat.put(IpecField.OSC_MOTOR, raw.oscMotor());
        flat.put(IpecField.OSC_PROPERTY, raw.oscProperty());
        flat.put(IpecField.IBNR_HEALTH, raw.ibnrHealth());
        flat.put(IpecField.IBNR_MOTOR, raw.ibnrMotor());
        flat.put(IpecField.IBNR_PROPERTY, raw.ibnrProperty());
        // Reinsurance recoverables
        flat.put(IpecField.REI_RECOVERABLES_OUTSTANDING, raw.reiRecoverablesOutstanding());
        flat.put(IpecField.REI_RECOVERABLES_IBNR, raw.reiRecoverablesIbnr());
        // Solvency
        flat.put(IpecField.SOL_ADMITTED_CAPITAL, solvency.admittedCapital());
        flat.put(IpecField.SOL_MIN_REQUIRED_CAPITAL, solvency.minRequiredCapital());
        flat.put(IpecField.SOL_MARGIN, solvency.margin());
        flat.put(IpecField.SOL_RATIO, solvency.ratio());

        return RegulatoryReportData.builder(
                        ReportKey.IPEC_QUARTERLY_RETURN, tenantId, periodStart, periodEnd, currency)
                .section(SECTION_META, subMap(flat,
                        IpecField.META_TENANT_NAME, IpecField.META_LICENCE_NUMBER,
                        IpecField.META_REPORTING_CURRENCY,
                        IpecField.META_PERIOD_START, IpecField.META_PERIOD_END))
                .section(SECTION_BALANCE_SHEET, subMap(flat,
                        IpecField.BS_TOTAL_ASSETS, IpecField.BS_TOTAL_LIABILITIES, IpecField.BS_TOTAL_EQUITY))
                .section(SECTION_REVENUE_ACCOUNT, subMap(flat,
                        IpecField.REV_GWP_HEALTH, IpecField.REV_GWP_MOTOR, IpecField.REV_GWP_PROPERTY,
                        IpecField.REV_CEDED_REINSURANCE, IpecField.REV_NET_EARNED_PREMIUM,
                        IpecField.REV_NET_CLAIMS_INCURRED, IpecField.REV_MANAGEMENT_EXPENSES,
                        IpecField.REV_UNDERWRITING_RESULT))
                .section(SECTION_UPR, subMap(flat,
                        IpecField.UPR_HEALTH, IpecField.UPR_MOTOR, IpecField.UPR_PROPERTY))
                .section(SECTION_OSC, subMap(flat,
                        IpecField.OSC_HEALTH, IpecField.OSC_MOTOR, IpecField.OSC_PROPERTY))
                .section(SECTION_IBNR, subMap(flat,
                        IpecField.IBNR_HEALTH, IpecField.IBNR_MOTOR, IpecField.IBNR_PROPERTY))
                .section(SECTION_REINSURANCE, subMap(flat,
                        IpecField.REI_RECOVERABLES_OUTSTANDING, IpecField.REI_RECOVERABLES_IBNR))
                .section(SECTION_SOLVENCY, subMap(flat,
                        IpecField.SOL_ADMITTED_CAPITAL, IpecField.SOL_MIN_REQUIRED_CAPITAL,
                        IpecField.SOL_MARGIN, IpecField.SOL_RATIO))
                .build();
    }

    /** Extract the flat field/value map for a single section, preserving order. */
    private static Map<String, Object> subMap(Map<IpecField, Object> flat, IpecField... keys) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (IpecField k : keys) {
            out.put(k.name(), flat.get(k));
        }
        return out;
    }

    /**
     * Rebuild a {@code Map<IpecField, Object>} from the composed sections
     * — used by the XLSX writer to fill the template via the cell map.
     */
    public static Map<IpecField, Object> toCellValueMap(RegulatoryReportData data) {
        Map<IpecField, Object> out = new EnumMap<>(IpecField.class);
        for (Map<String, Object> section : data.sections().values()) {
            for (Map.Entry<String, Object> e : section.entrySet()) {
                try {
                    IpecField key = IpecField.valueOf(e.getKey());
                    out.put(key, e.getValue());
                } catch (IllegalArgumentException ignored) {
                    // Foreign section entries are tolerated — the shaper is the sole
                    // populator today so this can't fire in practice.
                }
            }
        }
        return out;
    }

    private static java.math.BigDecimal sum(java.math.BigDecimal... values) {
        java.math.BigDecimal acc = java.math.BigDecimal.ZERO;
        for (java.math.BigDecimal v : values) {
            if (v != null) acc = acc.add(v);
        }
        return acc;
    }
}
