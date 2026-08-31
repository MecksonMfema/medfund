package com.medfund.finance.regulatory.tax.vat;

import com.medfund.finance.regulatory.service.RegulatoryReportData;
import com.medfund.shared.report.regulatory.RegulatoryTemplateService;
import com.medfund.shared.report.regulatory.TemplateResolution;
import com.medfund.shared.report.regulatory.TemplateSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Workbook;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Locale;
import java.util.UUID;

/**
 * Composes the bundled (or tenant-override) VAT Return template with the
 * shaped {@link RegulatoryReportData}, returning the XLSX bytes.
 *
 * <p>Unlike IPEC / CMS / NAIC / PMB there are TWO country-specific
 * bundled templates —
 * {@code report-templates/vat/zw-vat-return-v_SYNTHETIC_YYYY-MM-DD.xlsx}
 * and {@code za-vat-return-v_SYNTHETIC_YYYY-MM-DD.xlsx}. The country is
 * discovered from the shaped data's country cell
 * ({@link VatField#META_COUNTRY}) and encoded into the report-key
 * segment of the classpath lookup — the same {@link VatCellMap} covers
 * both because the two synthetic templates ship with matching named ranges.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VatXlsxService {

    /** The regulator segment of the {@code report-templates/{regulator}/} classpath. */
    static final String REGULATOR = "vat";

    private final RegulatoryTemplateService templateService;
    private final VatCellMap cellMap;

    public Mono<VatRenderResult> render(UUID tenantId, RegulatoryReportData data) {
        String reportKeyFile = pickReportKeyFile(data);
        return templateService.load(tenantId, REGULATOR, reportKeyFile, data.periodEnd())
                .map(resolution -> renderToBytes(resolution, data));
    }

    /**
     * Derive the country-specific template file stem from the shaped data
     * — {@code zw-vat-return} for ZW tenants, {@code za-vat-return} for
     * ZA. Falls back to the ZA template when the country cell is missing
     * (defensive; the shaper always populates it in practice — the
     * `@RequiresCountry` gate on the controller also rejects other
     * countries upstream).
     */
    static String pickReportKeyFile(RegulatoryReportData data) {
        Object country = data.sections().getOrDefault("meta", java.util.Map.of())
                .get(VatField.META_COUNTRY.name());
        String code = country != null ? country.toString().toUpperCase(Locale.ROOT) : "";
        return switch (code) {
            case "ZW" -> "zw-vat-return";
            case "ZA" -> "za-vat-return";
            default -> {
                log.warn("[vat-xlsx] no country on shaped data (was '{}'); defaulting to ZA template", country);
                yield "za-vat-return";
            }
        };
    }

    private VatRenderResult renderToBytes(TemplateResolution resolution, RegulatoryReportData data) {
        Workbook wb = resolution.workbook();
        try {
            templateService.fill(wb, cellMap, VatReturnReportShaper.toCellValueMap(data));
            byte[] bytes = templateService.toBytes(wb);
            return new VatRenderResult(bytes, resolution.source(), resolution.versionLabel());
        } finally {
            try {
                wb.close();
            } catch (Exception e) {
                log.warn("[vat-xlsx] template workbook close failed: {}", e.getMessage());
            }
        }
    }

    /** Bytes + provenance so the controller can propagate the synthetic-banner hint. */
    public record VatRenderResult(
            byte[] bytes,
            TemplateSource source,
            String versionLabel) {}
}
