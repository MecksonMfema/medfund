package com.medfund.finance.regulatory.ipec;

import com.medfund.finance.regulatory.service.RegulatoryReportData;
import com.medfund.shared.report.regulatory.RegulatoryTemplateService;
import com.medfund.shared.report.regulatory.TemplateResolution;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Workbook;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Composes the bundled (or tenant-override) IPEC quarterly return template
 * with the shaped {@link RegulatoryReportData}, returning the XLSX bytes
 * for the HTTP download body + optional
 * {@link com.medfund.finance.regulatory.service.RegulatorySubmissionService#submit
 * submission} archive.
 *
 * <p>Delegates all cell writes to
 * {@link RegulatoryTemplateService#fill}, which pattern-matches over the
 * {@link IpecCellMap} entries. If the resolved template comes from a
 * synthetic bundle
 * ({@link com.medfund.shared.report.regulatory.TemplateSource#BUNDLED_SYNTHETIC})
 * the {@link IpecRenderResult#source} propagates so the Angular page
 * renders the synthetic-template warning banner.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IpecXlsxService {

    /** The regulator segment of the {@code report-templates/{regulator}/} classpath. */
    static final String REGULATOR = "ipec";
    /** The report-key segment inside that folder — {@code {reportKey}-v_YYYY-MM-DD.xlsx}. */
    static final String REPORT_KEY_FILE = "ipec-quarterly-return";

    private final RegulatoryTemplateService templateService;
    private final IpecCellMap cellMap;

    public Mono<IpecRenderResult> render(UUID tenantId, RegulatoryReportData data) {
        return templateService.load(tenantId, REGULATOR, REPORT_KEY_FILE, data.periodEnd())
                .map(resolution -> renderToBytes(resolution, data));
    }

    private IpecRenderResult renderToBytes(TemplateResolution resolution, RegulatoryReportData data) {
        Workbook wb = resolution.workbook();
        try {
            templateService.fill(wb, cellMap, IpecReportShaper.toCellValueMap(data));
            byte[] bytes = templateService.toBytes(wb);
            return new IpecRenderResult(bytes, resolution.source(), resolution.versionLabel());
        } finally {
            try {
                wb.close();
            } catch (Exception e) {
                log.warn("[ipec-xlsx] template workbook close failed: {}", e.getMessage());
            }
        }
    }

    /** Bytes + provenance so the controller can propagate the synthetic-banner hint. */
    public record IpecRenderResult(
            byte[] bytes,
            com.medfund.shared.report.regulatory.TemplateSource source,
            String versionLabel) {}
}
