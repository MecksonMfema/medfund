package com.medfund.finance.regulatory.tax.vat;

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
 * Shapes the VAT Return for a ({@code tenantId}, period) tuple.
 * Delegates raw base aggregation to {@link VatRawDataProvider}; resolves
 * per-category rates via {@link VatRateReader} against the tenant's
 * effective {@code tenant_tax_config} rows; derives output + input VAT
 * amounts via {@link VatCalculator}; assembles the result into a
 * {@link RegulatoryReportData} whose section keys line up with what the
 * XLSX writer + {@link VatCellMap} consume.
 *
 * <p>Reporting currency follows the tenant's country per
 * {@link RegulatoryReportCurrency#countryNativeFor(ReportKey, String)}
 * — {@code ZWL} for ZW tenants, {@code ZAR} for ZA. A client-supplied
 * currency override is rejected 422 upstream by
 * {@link com.medfund.finance.regulatory.service.RegulatoryReportShapingService#rejectClientCurrencyOverride}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VatReturnReportShaper implements PerRegulatorShaper {

    public static final String SECTION_META = "meta";
    public static final String SECTION_OUTPUT = "output";
    public static final String SECTION_INPUT = "input";
    public static final String SECTION_SUMMARY = "summary";

    private final VatRawDataProvider rawDataProvider;
    private final VatRateReader rateReader;
    private final VatCalculator calculator;

    @Override
    public ReportKey supportedKey() {
        return ReportKey.VAT_RETURN;
    }

    @Override
    public Mono<RegulatoryReportData> shape(UUID tenantId,
                                            LocalDate periodStart,
                                            LocalDate periodEnd,
                                            String tenantCountryCode) {
        String currency = RegulatoryReportCurrency.resolveOrThrow(
                ReportKey.VAT_RETURN, tenantCountryCode);
        return Mono.zip(
                        rawDataProvider.load(tenantId, periodStart, periodEnd),
                        rateReader.resolve(tenantId, tenantCountryCode, currency, periodEnd))
                .map(t -> compose(t.getT1(), t.getT2(), tenantId,
                        tenantCountryCode, periodStart, periodEnd, currency));
    }

    /**
     * Package-private compose seam — tests drive raw data + rates directly
     * rather than mocking the provider + reader. Public callers go
     * through {@link #shape}.
     */
    RegulatoryReportData compose(VatRawData raw,
                                 VatRates rates,
                                 UUID tenantId,
                                 String countryCode,
                                 LocalDate periodStart,
                                 LocalDate periodEnd,
                                 String currency) {
        VatCalculator.Computed c = calculator.compute(raw, rates);

        Map<VatField, Object> flat = new EnumMap<>(VatField.class);
        // Meta
        flat.put(VatField.META_VENDOR_NAME, raw.vendorName());
        flat.put(VatField.META_VAT_REGISTRATION_NUMBER, raw.vatRegistrationNumber());
        flat.put(VatField.META_COUNTRY, countryCode);
        flat.put(VatField.META_REPORTING_CURRENCY, currency);
        flat.put(VatField.META_PERIOD_START, periodStart);
        flat.put(VatField.META_PERIOD_END, periodEnd);
        // Output
        flat.put(VatField.OUTPUT_PREMIUM_BASE, c.premiumBase());
        flat.put(VatField.OUTPUT_PREMIUM_VAT, c.premiumVat());
        flat.put(VatField.OUTPUT_ADMIN_FEE_BASE, c.adminFeeBase());
        flat.put(VatField.OUTPUT_ADMIN_FEE_VAT, c.adminFeeVat());
        flat.put(VatField.OUTPUT_COMMISSION_BASE, c.commissionBase());
        flat.put(VatField.OUTPUT_COMMISSION_VAT, c.commissionVat());
        flat.put(VatField.OUTPUT_OTHER_BASE, c.otherOutputBase());
        flat.put(VatField.OUTPUT_OTHER_VAT, c.otherOutputVat());
        flat.put(VatField.OUTPUT_STANDARD_RATED_TOTAL_BASE, c.outputStandardTotalBase());
        flat.put(VatField.OUTPUT_STANDARD_RATED_TOTAL_VAT, c.outputStandardTotalVat());
        flat.put(VatField.OUTPUT_ZERO_RATED_TOTAL_BASE, c.outputZeroRatedTotalBase());
        // Input
        flat.put(VatField.INPUT_ADMIN_EXPENSES_BASE, c.inputAdminExpensesBase());
        flat.put(VatField.INPUT_ADMIN_EXPENSES_VAT, c.inputAdminExpensesVat());
        flat.put(VatField.INPUT_PROFESSIONAL_FEES_BASE, c.inputProfessionalFeesBase());
        flat.put(VatField.INPUT_PROFESSIONAL_FEES_VAT, c.inputProfessionalFeesVat());
        flat.put(VatField.INPUT_OTHER_BASE, c.inputOtherBase());
        flat.put(VatField.INPUT_OTHER_VAT, c.inputOtherVat());
        flat.put(VatField.INPUT_TOTAL_BASE, c.inputTotalBase());
        flat.put(VatField.INPUT_TOTAL_VAT, c.inputTotalVat());
        // Summary
        flat.put(VatField.SUMMARY_NET_VAT_PAYABLE, c.netVatPayable());

        return RegulatoryReportData.builder(
                        ReportKey.VAT_RETURN, tenantId, periodStart, periodEnd, currency)
                .section(SECTION_META, subMap(flat,
                        VatField.META_VENDOR_NAME, VatField.META_VAT_REGISTRATION_NUMBER,
                        VatField.META_COUNTRY, VatField.META_REPORTING_CURRENCY,
                        VatField.META_PERIOD_START, VatField.META_PERIOD_END))
                .section(SECTION_OUTPUT, subMap(flat,
                        VatField.OUTPUT_PREMIUM_BASE, VatField.OUTPUT_PREMIUM_VAT,
                        VatField.OUTPUT_ADMIN_FEE_BASE, VatField.OUTPUT_ADMIN_FEE_VAT,
                        VatField.OUTPUT_COMMISSION_BASE, VatField.OUTPUT_COMMISSION_VAT,
                        VatField.OUTPUT_OTHER_BASE, VatField.OUTPUT_OTHER_VAT,
                        VatField.OUTPUT_STANDARD_RATED_TOTAL_BASE, VatField.OUTPUT_STANDARD_RATED_TOTAL_VAT,
                        VatField.OUTPUT_ZERO_RATED_TOTAL_BASE))
                .section(SECTION_INPUT, subMap(flat,
                        VatField.INPUT_ADMIN_EXPENSES_BASE, VatField.INPUT_ADMIN_EXPENSES_VAT,
                        VatField.INPUT_PROFESSIONAL_FEES_BASE, VatField.INPUT_PROFESSIONAL_FEES_VAT,
                        VatField.INPUT_OTHER_BASE, VatField.INPUT_OTHER_VAT,
                        VatField.INPUT_TOTAL_BASE, VatField.INPUT_TOTAL_VAT))
                .section(SECTION_SUMMARY, subMap(flat,
                        VatField.SUMMARY_NET_VAT_PAYABLE))
                .build();
    }

    /** Extract the flat field/value map for a single section, preserving order. */
    private static Map<String, Object> subMap(Map<VatField, Object> flat, VatField... keys) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (VatField k : keys) {
            out.put(k.name(), flat.get(k));
        }
        return out;
    }

    /**
     * Rebuild a {@code Map<VatField, Object>} from the composed sections
     * — used by the XLSX writer to fill the template via the cell map.
     */
    public static Map<VatField, Object> toCellValueMap(RegulatoryReportData data) {
        Map<VatField, Object> out = new EnumMap<>(VatField.class);
        for (Map<String, Object> section : data.sections().values()) {
            for (Map.Entry<String, Object> e : section.entrySet()) {
                try {
                    VatField key = VatField.valueOf(e.getKey());
                    out.put(key, e.getValue());
                } catch (IllegalArgumentException ignored) {
                    // Foreign entries tolerated.
                }
            }
        }
        return out;
    }
}
