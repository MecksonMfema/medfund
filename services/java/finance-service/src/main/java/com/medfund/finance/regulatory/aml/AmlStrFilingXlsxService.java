package com.medfund.finance.regulatory.aml;

import com.medfund.finance.regulatory.aml.entity.SuspiciousTransactionAlert;
import com.medfund.shared.report.regulatory.RegulatoryTemplateService;
import com.medfund.shared.report.regulatory.TemplateResolution;
import com.medfund.shared.report.regulatory.TemplateSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Workbook;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Composes a bundled (or tenant-override) per-STR filing template with
 * the shaped alert data (Phase 26). Country picker maps ZW → FIU goAML,
 * ZA → FIC, US → FinCEN SAR. Unknown countries fall back to the ZA
 * template with a WARN log — matches {@link AmlXlsxService}'s posture
 * for the periodic summary.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AmlStrFilingXlsxService {

    /** Regulator segment of the {@code report-templates/{regulator}/} classpath. */
    static final String REGULATOR = "aml";

    private final RegulatoryTemplateService templateService;
    private final AmlStrFilingCellMap cellMap;
    private final AmlStrFilingShaper shaper;

    /**
     * Render a per-STR filing XLSX for {@code alert} in {@code tenantId}'s
     * country. The effective template date is the alert's filed date if
     * present, else raised — either way, resolves against the highest
     * bundled template ≤ that date.
     */
    public Mono<AmlStrFilingRenderResult> render(UUID tenantId,
                                                  SuspiciousTransactionAlert alert,
                                                  String reportingEntityName,
                                                  String regulatorReference,
                                                  String tenantCountryCode) {
        String reportKeyFile = pickReportKeyFile(tenantCountryCode);
        LocalDate effectiveDate = effectiveTemplateDate(alert);
        Map<AmlStrField, Object> data = shaper.compose(
                alert, reportingEntityName, regulatorReference, tenantCountryCode);
        return templateService.load(tenantId, REGULATOR, reportKeyFile, effectiveDate)
                .map(resolution -> renderToBytes(resolution, data));
    }

    /**
     * Country → template stem. All three synthetic templates ship in Phase 26.
     * Unknown / null countries fall back to the ZA (FIC) template with a
     * WARN log — same posture as the periodic-summary picker so the
     * download always produces bytes.
     */
    static String pickReportKeyFile(String countryCode) {
        String c = countryCode != null ? countryCode.toUpperCase(Locale.ROOT) : "";
        return switch (c) {
            case "ZW" -> "zw-str-filing";
            case "ZA" -> "za-str-filing";
            case "US" -> "us-str-filing";
            default -> {
                log.warn("[aml-str-xlsx] unknown country '{}' — falling back to za-str-filing template",
                        countryCode);
                yield "za-str-filing";
            }
        };
    }

    private static LocalDate effectiveTemplateDate(SuspiciousTransactionAlert alert) {
        if (alert.getFiledAt() != null) return alert.getFiledAt().toLocalDate();
        if (alert.getRaisedAt() != null) return alert.getRaisedAt().toLocalDate();
        return LocalDate.now();
    }

    private AmlStrFilingRenderResult renderToBytes(TemplateResolution resolution,
                                                    Map<AmlStrField, Object> data) {
        Workbook wb = resolution.workbook();
        try {
            templateService.fill(wb, cellMap, data);
            byte[] bytes = templateService.toBytes(wb);
            return new AmlStrFilingRenderResult(bytes, resolution.source(), resolution.versionLabel());
        } finally {
            try {
                wb.close();
            } catch (Exception e) {
                log.warn("[aml-str-xlsx] template workbook close failed: {}", e.getMessage());
            }
        }
    }

    public record AmlStrFilingRenderResult(byte[] bytes, TemplateSource source, String versionLabel) {}
}
