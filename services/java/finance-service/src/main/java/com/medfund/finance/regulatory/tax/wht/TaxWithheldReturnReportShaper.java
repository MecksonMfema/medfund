package com.medfund.finance.regulatory.tax.wht;

import com.medfund.finance.regulatory.service.PerRegulatorShaper;
import com.medfund.finance.regulatory.service.RegulatoryReportData;
import com.medfund.shared.report.ReportKey;
import com.medfund.shared.report.regulatory.RegulatoryReportCurrency;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Shapes the Withholding-Tax return for a ({@code tenantId}, period)
 * tuple. Delegates raw base aggregation to
 * {@link TaxWithheldRawDataProvider}; resolves per-category rates via
 * {@link TaxWithheldRateReader}; derives per-category + total WHT
 * amounts via {@link TaxWithheldCalculator}; assembles the result
 * into a {@link RegulatoryReportData} whose section keys line up with
 * what the XLSX writer + {@link TaxWithheldCellMap} consume.
 *
 * <p>Reporting currency follows the tenant's country
 * (ZWL for ZW, ZAR for ZA) — a client-supplied override is rejected
 * 422 upstream.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaxWithheldReturnReportShaper implements PerRegulatorShaper {

    public static final String SECTION_META = "meta";
    public static final String SECTION_CATEGORIES = "categories";
    public static final String SECTION_TOTALS = "totals";

    private final TaxWithheldRawDataProvider rawDataProvider;
    private final TaxWithheldRateReader rateReader;
    private final TaxWithheldCalculator calculator;

    @Override
    public ReportKey supportedKey() {
        return ReportKey.TAX_WITHHELD_RETURN;
    }

    @Override
    public Mono<RegulatoryReportData> shape(UUID tenantId,
                                            LocalDate periodStart,
                                            LocalDate periodEnd,
                                            String tenantCountryCode) {
        String currency = RegulatoryReportCurrency.resolveOrThrow(
                ReportKey.TAX_WITHHELD_RETURN, tenantCountryCode);
        return Mono.zip(
                        rawDataProvider.load(tenantId, periodStart, periodEnd),
                        rateReader.resolve(tenantId, tenantCountryCode, currency, periodEnd))
                .map(t -> compose(t.getT1(), t.getT2(), tenantId,
                        tenantCountryCode, periodStart, periodEnd, currency));
    }

    /** Package-private compose seam — tests drive raw + rates directly. */
    RegulatoryReportData compose(TaxWithheldRawData raw,
                                 TaxWithheldRates rates,
                                 UUID tenantId,
                                 String countryCode,
                                 LocalDate periodStart,
                                 LocalDate periodEnd,
                                 String currency) {
        TaxWithheldCalculator.Computed c = calculator.compute(raw, rates);

        Map<TaxWithheldField, Object> flat = new EnumMap<>(TaxWithheldField.class);
        // Meta
        flat.put(TaxWithheldField.META_AGENT_NAME, raw.agentName());
        flat.put(TaxWithheldField.META_TAX_IDENTIFICATION_NUMBER, raw.taxIdentificationNumber());
        flat.put(TaxWithheldField.META_COUNTRY, countryCode);
        flat.put(TaxWithheldField.META_REPORTING_CURRENCY, currency);
        flat.put(TaxWithheldField.META_PERIOD_START, periodStart);
        flat.put(TaxWithheldField.META_PERIOD_END, periodEnd);
        // Per-category
        flat.put(TaxWithheldField.CAT_COMMISSION_BASE,        c.commissionBase());
        flat.put(TaxWithheldField.CAT_COMMISSION_WHT,         c.commissionWht());
        flat.put(TaxWithheldField.CAT_PROFESSIONAL_FEES_BASE, c.professionalFeesBase());
        flat.put(TaxWithheldField.CAT_PROFESSIONAL_FEES_WHT,  c.professionalFeesWht());
        flat.put(TaxWithheldField.CAT_DIVIDENDS_BASE,         c.dividendsBase());
        flat.put(TaxWithheldField.CAT_DIVIDENDS_WHT,          c.dividendsWht());
        flat.put(TaxWithheldField.CAT_OTHER_BASE,             c.otherBase());
        flat.put(TaxWithheldField.CAT_OTHER_WHT,              c.otherWht());
        // Totals
        flat.put(TaxWithheldField.TOTAL_PAYMENTS_BASE,        c.totalPaymentsBase());
        flat.put(TaxWithheldField.TOTAL_WITHHOLDING_PAYABLE,  c.totalWithholdingPayable());

        return RegulatoryReportData.builder(
                        ReportKey.TAX_WITHHELD_RETURN, tenantId, periodStart, periodEnd, currency)
                .section(SECTION_META, subMap(flat,
                        TaxWithheldField.META_AGENT_NAME, TaxWithheldField.META_TAX_IDENTIFICATION_NUMBER,
                        TaxWithheldField.META_COUNTRY, TaxWithheldField.META_REPORTING_CURRENCY,
                        TaxWithheldField.META_PERIOD_START, TaxWithheldField.META_PERIOD_END))
                .section(SECTION_CATEGORIES, subMap(flat,
                        TaxWithheldField.CAT_COMMISSION_BASE, TaxWithheldField.CAT_COMMISSION_WHT,
                        TaxWithheldField.CAT_PROFESSIONAL_FEES_BASE, TaxWithheldField.CAT_PROFESSIONAL_FEES_WHT,
                        TaxWithheldField.CAT_DIVIDENDS_BASE, TaxWithheldField.CAT_DIVIDENDS_WHT,
                        TaxWithheldField.CAT_OTHER_BASE, TaxWithheldField.CAT_OTHER_WHT))
                .section(SECTION_TOTALS, subMap(flat,
                        TaxWithheldField.TOTAL_PAYMENTS_BASE,
                        TaxWithheldField.TOTAL_WITHHOLDING_PAYABLE))
                .build();
    }

    private static Map<String, Object> subMap(Map<TaxWithheldField, Object> flat, TaxWithheldField... keys) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (TaxWithheldField k : keys) {
            out.put(k.name(), flat.get(k));
        }
        return out;
    }

    public static Map<TaxWithheldField, Object> toCellValueMap(RegulatoryReportData data) {
        Map<TaxWithheldField, Object> out = new EnumMap<>(TaxWithheldField.class);
        for (Map<String, Object> section : data.sections().values()) {
            for (Map.Entry<String, Object> e : section.entrySet()) {
                try {
                    TaxWithheldField key = TaxWithheldField.valueOf(e.getKey());
                    out.put(key, e.getValue());
                } catch (IllegalArgumentException ignored) {
                    // Foreign entries tolerated.
                }
            }
        }
        return out;
    }
}
