package com.medfund.claims.report.schedule;

import com.medfund.claims.reports.provider.service.ProviderNetworkUtilizationWorkbookService;
import com.medfund.claims.service.ClaimsExcelService;
import com.medfund.shared.report.ReportKey;
import com.medfund.shared.report.ScheduledRenderRequest;
import com.medfund.shared.security.RequiresPermission;
import com.medfund.shared.security.SecurityEventPublisher;
import com.medfund.shared.tenant.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Phase 17 §A.3 — scheduled-render endpoints for claims-service-owned
 * cadenced keys (CLAIMS_SUMMARY, PROVIDER_NETWORK_UTILIZATION).
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/reports")
@Tag(name = "Scheduled report render",
        description = "Phase 17 §A.3 internal endpoints for finance-service to fetch claims report bytes.")
@RequiredArgsConstructor
public class ScheduledRenderController {

    private static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    private static final String PERM = "scheduled_report:render";

    private final ClaimsExcelService claimsExcelService;
    private final ProviderNetworkUtilizationWorkbookService providerNetworkService;
    private final SecurityEventPublisher securityEventPublisher;

    @PostMapping(value = "/CLAIMS_SUMMARY/scheduled-render", produces = {
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"})
    @Operation(summary = "Render CLAIMS_SUMMARY workbook for a scheduled fire.")
    @RequiresPermission(PERM)
    public Mono<ResponseEntity<byte[]>> renderClaimsSummary(@RequestBody ScheduledRenderRequest req) {
        log.debug("[scheduled-render] CLAIMS_SUMMARY tenant={} period={}..{}",
                req.tenantId(), req.periodStart(), req.periodEnd());
        return claimsExcelService.schemesReportExcel(
                        req.periodStart(), req.periodEnd(), req.reportingCurrency(), null)
                .contextWrite(c -> TenantContext.put(c, req.tenantId().toString()))
                .flatMap(bytes -> auditAndWrap(bytes, req, ReportKey.CLAIMS_SUMMARY));
    }

    @PostMapping(value = "/PROVIDER_NETWORK_UTILIZATION/scheduled-render", produces = {
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"})
    @Operation(summary = "Render PROVIDER_NETWORK_UTILIZATION workbook for a scheduled fire.")
    @RequiresPermission(PERM)
    public Mono<ResponseEntity<byte[]>> renderProviderNetworkUtilization(
            @RequestBody ScheduledRenderRequest req) {
        log.debug("[scheduled-render] PROVIDER_NETWORK_UTILIZATION tenant={} period={}..{}",
                req.tenantId(), req.periodStart(), req.periodEnd());
        return providerNetworkService.workbook(
                        req.periodStart(), req.periodEnd(), null, null, req.reportingCurrency())
                .contextWrite(c -> TenantContext.put(c, req.tenantId().toString()))
                .flatMap(bytes -> auditAndWrap(bytes, req, ReportKey.PROVIDER_NETWORK_UTILIZATION));
    }

    private Mono<ResponseEntity<byte[]>> auditAndWrap(byte[] bytes, ScheduledRenderRequest req,
                                                       ReportKey key) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("source", "SCHEDULED");
        details.put("scheduleId", req.scheduleId() != null ? req.scheduleId().toString() : null);
        details.put("cadence", req.cadenceLabel());
        if (req.periodStart() != null) details.put("periodStart", req.periodStart().toString());
        if (req.periodEnd() != null) details.put("periodEnd", req.periodEnd().toString());
        details.put("sizeBytes", bytes.length);
        return securityEventPublisher.publishDataAccess(
                        req.tenantId().toString(),
                        req.actorId() != null ? req.actorId().toString() : null,
                        req.actorEmail(),
                        key.name(),
                        details)
                .thenReturn(ResponseEntity.ok().contentType(XLSX).body(bytes));
    }
}
