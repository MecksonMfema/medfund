package com.medfund.finance.regulatory.tax.wht;

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
 * Composes the bundled (or tenant-override) Withholding-Tax return
 * template with the shaped {@link RegulatoryReportData}, returning the
 * XLSX bytes.
 *
 * <p>Bundled templates are country-specific
 * ({@code report-templates/tax-withheld/zw-tax-withheld-v_SYNTHETIC_2024-01-01.xlsx}
 * for ZW ITF12B, {@code za-tax-withheld-v_SYNTHETIC_2024-01-01.xlsx}
 * for the SARS IRP5-shape) — the same {@link TaxWithheldCellMap}
 * covers both because their named ranges match.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaxWithheldXlsxService {

    /** The regulator segment of the {@code report-templates/{regulator}/} classpath. */
    static final String REGULATOR = "tax-withheld";

    private final RegulatoryTemplateService templateService;
    private final TaxWithheldCellMap cellMap;

    public Mono<TaxWithheldRenderResult> render(UUID tenantId, RegulatoryReportData data) {
        String reportKeyFile = pickReportKeyFile(data);
        return templateService.load(tenantId, REGULATOR, reportKeyFile, data.periodEnd())
                .map(resolution -> renderToBytes(resolution, data));
    }

    /**
     * Country → template stem. Missing country falls back to ZA (the
     * `@RequiresCountry({"ZW","ZA"})` gate on the controller rejects
     * other countries upstream).
     */
    static String pickReportKeyFile(RegulatoryReportData data) {
        Object country = data.sections().getOrDefault("meta", java.util.Map.of())
                .get(TaxWithheldField.META_COUNTRY.name());
        String code = country != null ? country.toString().toUpperCase(Locale.ROOT) : "";
        return switch (code) {
            case "ZW" -> "zw-tax-withheld";
            case "ZA" -> "za-tax-withheld";
            default -> {
                log.warn("[wht-xlsx] no country on shaped data (was '{}'); defaulting to ZA template", country);
                yield "za-tax-withheld";
            }
        };
    }

    private TaxWithheldRenderResult renderToBytes(TemplateResolution resolution, RegulatoryReportData data) {
        Workbook wb = resolution.workbook();
        try {
            templateService.fill(wb, cellMap, TaxWithheldReturnReportShaper.toCellValueMap(data));
            byte[] bytes = templateService.toBytes(wb);
            return new TaxWithheldRenderResult(bytes, resolution.source(), resolution.versionLabel());
        } finally {
            try {
                wb.close();
            } catch (Exception e) {
                log.warn("[wht-xlsx] template workbook close failed: {}", e.getMessage());
            }
        }
    }

    public record TaxWithheldRenderResult(
            byte[] bytes,
            TemplateSource source,
            String versionLabel) {}
}
