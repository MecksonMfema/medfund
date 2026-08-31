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
import java.util.Map;
import java.util.UUID;

/**
 * Shapes the NAIC Schedule F for a ({@code tenantId}, year) tuple.
 * Delegates raw data assembly to {@link NaicScheduleFRawDataProvider};
 * runs each ceded stratum through {@link NaicScheduleFCalculator} using
 * the YAML provision percentages resolved for the period end; assembles
 * the result into a {@link RegulatoryReportData} whose section keys line
 * up with what the XLSX writer + {@link NaicFCellMap} consume.
 *
 * <p>Reporting currency is fixed at USD per
 * {@link RegulatoryReportCurrency#fixedFor(ReportKey)} — the currency-override
 * rejection lives on the controller through
 * {@link com.medfund.finance.regulatory.service.RegulatoryReportShapingService#rejectClientCurrencyOverride}.
 */
@Slf4j
@Component
public class NaicScheduleFReportShaper implements PerRegulatorShaper {

    public static final String SECTION_META = "meta";
    public static final String SECTION_ASSUMED = "assumed";
    public static final String SECTION_CEDED_AFFILIATED = "ceded_affiliated";
    public static final String SECTION_CEDED_AUTHORIZED = "ceded_authorized";
    public static final String SECTION_CEDED_UNAUTHORIZED = "ceded_unauthorized";
    public static final String SECTION_CEDED_CERTIFIED = "ceded_certified";
    public static final String SECTION_TOTALS = "totals";

    private final NaicScheduleFRawDataProvider rawDataProvider;
    private final NaicScheduleFCalculator calculator;
    /** Phase 15b — nullable so unit tests can construct without the rules-engine wiring. */
    private final @Nullable RegulatoryParameterResolver parameterResolver;

    /** Spring wires this constructor — {@code RegulatoryParameterResolver} is a required bean. */
    @Autowired
    public NaicScheduleFReportShaper(NaicScheduleFRawDataProvider rawDataProvider,
                                     NaicScheduleFCalculator calculator,
                                     RegulatoryParameterResolver parameterResolver) {
        this.rawDataProvider = rawDataProvider;
        this.calculator = calculator;
        this.parameterResolver = parameterResolver;
    }

    /** Test constructor — YAML-only fallback (no resolver = bundled defaults win). */
    public NaicScheduleFReportShaper(NaicScheduleFRawDataProvider rawDataProvider,
                                     NaicScheduleFCalculator calculator) {
        this(rawDataProvider, calculator, null);
    }

    @Override
    public ReportKey supportedKey() {
        return ReportKey.NAIC_SCHEDULE_F;
    }

    @Override
    public Mono<RegulatoryReportData> shape(UUID tenantId,
                                            LocalDate periodStart,
                                            LocalDate periodEnd,
                                            String tenantCountryCode) {
        String currency = RegulatoryReportCurrency.resolveOrThrow(
                ReportKey.NAIC_SCHEDULE_F, tenantCountryCode);
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
     * Package-private compose seam — tests drive raw data directly rather
     * than mocking the provider. Public callers go through {@link #shape}.
     * This YAML-only overload keeps every existing golden-fixture test call
     * site unchanged; the resolver-based path goes through the
     * {@link #compose(NaicScheduleFRawData, NaicScheduleFParameters, UUID, LocalDate, LocalDate, String)}
     * overload which takes pre-resolved parameters.
     */
    RegulatoryReportData compose(NaicScheduleFRawData raw,
                                 UUID tenantId,
                                 LocalDate periodStart,
                                 LocalDate periodEnd,
                                 String currency) {
        return compose(raw, calculator.resolveParameters(periodEnd),
                tenantId, periodStart, periodEnd, currency);
    }

    /** Primary compose seam — takes pre-resolved parameters (rules-engine + YAML fallback). */
    RegulatoryReportData compose(NaicScheduleFRawData raw,
                                 NaicScheduleFParameters parameters,
                                 UUID tenantId,
                                 LocalDate periodStart,
                                 LocalDate periodEnd,
                                 String currency) {
        NaicScheduleFCalculator.StratumResult affiliated = calculator.computeStratum(
                raw.cededAffiliatedPremiums(),
                raw.cededAffiliatedLossesPaid(),
                raw.cededAffiliatedLossesUnpaid());
        NaicScheduleFCalculator.StratumResult authorized = calculator.computeStratum(
                raw.cededAuthorizedPremiums(),
                raw.cededAuthorizedLossesPaid(),
                raw.cededAuthorizedLossesUnpaid());
        NaicScheduleFCalculator.StratumResult unauthorized = calculator.computeStratum(
                raw.cededUnauthorizedPremiums(),
                raw.cededUnauthorizedLossesPaid(),
                raw.cededUnauthorizedLossesUnpaid());
        NaicScheduleFCalculator.StratumResult certified = calculator.computeStratum(
                raw.cededCertifiedPremiums(),
                raw.cededCertifiedLossesPaid(),
                raw.cededCertifiedLossesUnpaid());
        // Assumed side is informational — normalise scale but skip provision plumbing.
        NaicScheduleFCalculator.StratumResult assumed = calculator.computeStratum(
                raw.assumedPremiums(),
                raw.assumedLossesPaid(),
                raw.assumedLossesUnpaid());

        NaicScheduleFCalculator.CededTotalsResult totals = calculator.computeCededTotals(
                affiliated, authorized, unauthorized, certified, parameters);

        Map<NaicFField, Object> flat = new EnumMap<>(NaicFField.class);
        // Meta
        flat.put(NaicFField.META_COMPANY_NAME, raw.companyName());
        flat.put(NaicFField.META_NAIC_CODE, raw.naicCode());
        flat.put(NaicFField.META_GROUP_CODE, raw.groupCode());
        flat.put(NaicFField.META_FEIN, raw.fein());
        flat.put(NaicFField.META_STATE, raw.stateOfDomicile());
        flat.put(NaicFField.META_REPORTING_CURRENCY, currency);
        flat.put(NaicFField.META_PERIOD_START, periodStart);
        flat.put(NaicFField.META_PERIOD_END, periodEnd);
        // Part 1 — Assumed
        flat.put(NaicFField.P1_ASSUMED_PREMIUMS, assumed.premiums());
        flat.put(NaicFField.P1_ASSUMED_LOSSES_PAID, assumed.lossesPaid());
        flat.put(NaicFField.P1_ASSUMED_LOSSES_UNPAID, assumed.lossesUnpaid());
        // Part 2 — Ceded affiliated
        flat.put(NaicFField.P2_CEDED_AFFILIATED_PREMIUMS, affiliated.premiums());
        flat.put(NaicFField.P2_CEDED_AFFILIATED_LOSSES_PAID, affiliated.lossesPaid());
        flat.put(NaicFField.P2_CEDED_AFFILIATED_LOSSES_UNPAID, affiliated.lossesUnpaid());
        // Part 3 — Ceded authorized
        flat.put(NaicFField.P3_CEDED_AUTHORIZED_PREMIUMS, authorized.premiums());
        flat.put(NaicFField.P3_CEDED_AUTHORIZED_LOSSES_PAID, authorized.lossesPaid());
        flat.put(NaicFField.P3_CEDED_AUTHORIZED_LOSSES_UNPAID, authorized.lossesUnpaid());
        // Part 4 — Ceded unauthorized
        flat.put(NaicFField.P4_CEDED_UNAUTHORIZED_PREMIUMS, unauthorized.premiums());
        flat.put(NaicFField.P4_CEDED_UNAUTHORIZED_LOSSES_PAID, unauthorized.lossesPaid());
        flat.put(NaicFField.P4_CEDED_UNAUTHORIZED_LOSSES_UNPAID, unauthorized.lossesUnpaid());
        // Part 5 — Ceded certified
        flat.put(NaicFField.P5_CEDED_CERTIFIED_PREMIUMS, certified.premiums());
        flat.put(NaicFField.P5_CEDED_CERTIFIED_LOSSES_PAID, certified.lossesPaid());
        flat.put(NaicFField.P5_CEDED_CERTIFIED_LOSSES_UNPAID, certified.lossesUnpaid());
        // Totals
        flat.put(NaicFField.TOTAL_CEDED_PREMIUMS, totals.totalCededPremiums());
        flat.put(NaicFField.TOTAL_CEDED_LOSSES_PAID, totals.totalCededLossesPaid());
        flat.put(NaicFField.TOTAL_CEDED_LOSSES_UNPAID, totals.totalCededLossesUnpaid());
        flat.put(NaicFField.TOTAL_REINSURANCE_RECOVERABLE, totals.totalReinsuranceRecoverable());
        flat.put(NaicFField.PROVISION_FOR_REINSURANCE, totals.provisionForReinsurance());
        flat.put(NaicFField.NET_REINSURANCE_POSITION, totals.netReinsurancePosition());

        return RegulatoryReportData.builder(
                        ReportKey.NAIC_SCHEDULE_F, tenantId, periodStart, periodEnd, currency)
                .section(SECTION_META, subMap(flat,
                        NaicFField.META_COMPANY_NAME, NaicFField.META_NAIC_CODE,
                        NaicFField.META_GROUP_CODE, NaicFField.META_FEIN,
                        NaicFField.META_STATE, NaicFField.META_REPORTING_CURRENCY,
                        NaicFField.META_PERIOD_START, NaicFField.META_PERIOD_END))
                .section(SECTION_ASSUMED, subMap(flat,
                        NaicFField.P1_ASSUMED_PREMIUMS,
                        NaicFField.P1_ASSUMED_LOSSES_PAID,
                        NaicFField.P1_ASSUMED_LOSSES_UNPAID))
                .section(SECTION_CEDED_AFFILIATED, subMap(flat,
                        NaicFField.P2_CEDED_AFFILIATED_PREMIUMS,
                        NaicFField.P2_CEDED_AFFILIATED_LOSSES_PAID,
                        NaicFField.P2_CEDED_AFFILIATED_LOSSES_UNPAID))
                .section(SECTION_CEDED_AUTHORIZED, subMap(flat,
                        NaicFField.P3_CEDED_AUTHORIZED_PREMIUMS,
                        NaicFField.P3_CEDED_AUTHORIZED_LOSSES_PAID,
                        NaicFField.P3_CEDED_AUTHORIZED_LOSSES_UNPAID))
                .section(SECTION_CEDED_UNAUTHORIZED, subMap(flat,
                        NaicFField.P4_CEDED_UNAUTHORIZED_PREMIUMS,
                        NaicFField.P4_CEDED_UNAUTHORIZED_LOSSES_PAID,
                        NaicFField.P4_CEDED_UNAUTHORIZED_LOSSES_UNPAID))
                .section(SECTION_CEDED_CERTIFIED, subMap(flat,
                        NaicFField.P5_CEDED_CERTIFIED_PREMIUMS,
                        NaicFField.P5_CEDED_CERTIFIED_LOSSES_PAID,
                        NaicFField.P5_CEDED_CERTIFIED_LOSSES_UNPAID))
                .section(SECTION_TOTALS, subMap(flat,
                        NaicFField.TOTAL_CEDED_PREMIUMS,
                        NaicFField.TOTAL_CEDED_LOSSES_PAID,
                        NaicFField.TOTAL_CEDED_LOSSES_UNPAID,
                        NaicFField.TOTAL_REINSURANCE_RECOVERABLE,
                        NaicFField.PROVISION_FOR_REINSURANCE,
                        NaicFField.NET_REINSURANCE_POSITION))
                .build();
    }

    /** Extract the flat field/value map for a single section, preserving order. */
    private static Map<String, Object> subMap(Map<NaicFField, Object> flat, NaicFField... keys) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (NaicFField k : keys) {
            out.put(k.name(), flat.get(k));
        }
        return out;
    }

    /**
     * Rebuild a {@code Map<NaicFField, Object>} from the composed sections
     * — used by the XLSX writer to fill the template via the cell map.
     */
    public static Map<NaicFField, Object> toCellValueMap(RegulatoryReportData data) {
        Map<NaicFField, Object> out = new EnumMap<>(NaicFField.class);
        for (Map<String, Object> section : data.sections().values()) {
            for (Map.Entry<String, Object> e : section.entrySet()) {
                try {
                    NaicFField key = NaicFField.valueOf(e.getKey());
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
