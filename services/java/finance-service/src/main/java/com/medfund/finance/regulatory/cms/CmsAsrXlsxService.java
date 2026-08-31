package com.medfund.finance.regulatory.cms;

import com.medfund.finance.regulatory.service.RegulatoryReportData;
import com.medfund.shared.report.regulatory.RegulatoryTemplateService;
import com.medfund.shared.report.regulatory.TemplateResolution;
import com.medfund.shared.report.regulatory.TemplateSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Workbook;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Composes the bundled (or tenant-override) CMS Annual Statutory Return
 * template with the shaped {@link RegulatoryReportData}, returning the
 * XLSX bytes for the HTTP download body + optional
 * {@link com.medfund.finance.regulatory.service.RegulatorySubmissionService#submit
 * submission} archive.
 *
 * <p>Delegates all cell writes to
 * {@link RegulatoryTemplateService#fill}, which pattern-matches over the
 * {@link CmsCellMap} entries. If the resolved template comes from a
 * synthetic bundle
 * ({@link TemplateSource#BUNDLED_SYNTHETIC})
 * the {@link CmsAsrRenderResult#source} propagates so the Angular page
 * renders the synthetic-template warning banner.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CmsAsrXlsxService {

    /** The regulator segment of the {@code report-templates/{regulator}/} classpath. */
    static final String REGULATOR = "cms";
    /** The report-key segment inside that folder — {@code {reportKey}-v_YYYY-MM-DD.xlsx}. */
    static final String REPORT_KEY_FILE = "cms-asr";

    private final RegulatoryTemplateService templateService;
    private final CmsCellMap cellMap;

    public Mono<CmsAsrRenderResult> render(UUID tenantId, RegulatoryReportData data) {
        return templateService.load(tenantId, REGULATOR, REPORT_KEY_FILE, data.periodEnd())
                .map(resolution -> renderToBytes(resolution, data));
    }

    private CmsAsrRenderResult renderToBytes(TemplateResolution resolution, RegulatoryReportData data) {
        Workbook wb = resolution.workbook();
        try {
            templateService.fill(wb, cellMap, CmsAsrReportShaper.toCellValueMap(data));
            byte[] bytes = templateService.toBytes(wb);
            return new CmsAsrRenderResult(bytes, resolution.source(), resolution.versionLabel());
        } finally {
            try {
                wb.close();
            } catch (Exception e) {
                log.warn("[cms-xlsx] template workbook close failed: {}", e.getMessage());
            }
        }
    }

    /** Bytes + provenance so the controller can propagate the synthetic-banner hint. */
    public record CmsAsrRenderResult(
            byte[] bytes,
            TemplateSource source,
            String versionLabel) {}
}
