package com.medfund.finance.regulatory.aml;

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
 * Composes the bundled (or tenant-override) AML/STR periodic-summary
 * template with the shaped {@link RegulatoryReportData}, returning the
 * XLSX bytes.
 *
 * <p>Phase 25 ships ONE synthetic ZA template
 * ({@code report-templates/aml/za-aml-summary-v_SYNTHETIC_2024-01-01.xlsx}).
 * ZW + US per-STR filing templates land in Phase 26 alongside the FIU /
 * FIC / FinCEN per-STR XLSX shape. The shared {@link AmlCellMap} still
 * uses country-agnostic named ranges so a country-specific template
 * lookup only picks the base file — the fill logic is identical.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AmlXlsxService {

    /** The regulator segment of the {@code report-templates/{regulator}/} classpath. */
    static final String REGULATOR = "aml";

    private final RegulatoryTemplateService templateService;
    private final AmlCellMap cellMap;

    public Mono<AmlRenderResult> render(UUID tenantId, RegulatoryReportData data) {
        String reportKeyFile = pickReportKeyFile(data);
        return templateService.load(tenantId, REGULATOR, reportKeyFile, data.periodEnd())
                .map(resolution -> renderToBytes(resolution, data));
    }

    /**
     * Derive the country-specific template file stem from the shaped
     * data. Phase 25 only ships a ZA template; ZW / US fall back to
     * the ZA template with a WARN log until Phase 26 lands their real
     * synthetic templates. That keeps the report visually renderable
     * end-to-end for all three countries in the meantime.
     */
    static String pickReportKeyFile(RegulatoryReportData data) {
        Object country = data.sections().getOrDefault("meta", java.util.Map.of())
                .get(AmlField.META_COUNTRY.name());
        String code = country != null ? country.toString().toUpperCase(Locale.ROOT) : "";
        return switch (code) {
            case "ZA" -> "za-aml-summary";
            case "ZW", "US" -> {
                log.warn("[aml-xlsx] country {} template deferred to Phase 26 — using za-aml-summary "
                        + "as a placeholder so the report renders end-to-end.", code);
                yield "za-aml-summary";
            }
            default -> {
                log.warn("[aml-xlsx] no country on shaped data (was '{}'); defaulting to za-aml-summary", country);
                yield "za-aml-summary";
            }
        };
    }

    private AmlRenderResult renderToBytes(TemplateResolution resolution, RegulatoryReportData data) {
        Workbook wb = resolution.workbook();
        try {
            templateService.fill(wb, cellMap, AmlSummaryReportShaper.toCellValueMap(data));
            byte[] bytes = templateService.toBytes(wb);
            return new AmlRenderResult(bytes, resolution.source(), resolution.versionLabel());
        } finally {
            try {
                wb.close();
            } catch (Exception e) {
                log.warn("[aml-xlsx] template workbook close failed: {}", e.getMessage());
            }
        }
    }

    public record AmlRenderResult(byte[] bytes, TemplateSource source, String versionLabel) {}
}
