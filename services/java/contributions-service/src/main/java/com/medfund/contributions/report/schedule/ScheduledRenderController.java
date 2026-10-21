package com.medfund.contributions.report.schedule;

import com.medfund.contributions.premium.service.UprMovementWorkbookService;
import com.medfund.contributions.service.AgedBalancesExcelService;
import com.medfund.contributions.service.CashFlowForecastExcelService;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 17 §A.3 — scheduled-render endpoints for the three cadenced report
 * keys owned by contributions-service (AGED_DEBTORS, UPR_MOVEMENT,
 * CASH_FLOW_FORECAST_13W). Finance-service invokes these via M2M-token'd
 * WebClient calls under {@code @RequiresPermission("scheduled_report:render")}.
 *
 * <p>The request body carries {@code tenantId} + the human schedule
 * actor so the {@code SecurityEventPublisher.publishDataAccess} trail
 * records who owns the schedule, not the service account that made the
 * HTTP call.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/reports")
@Tag(name = "Scheduled report render",
        description = "Phase 17 §A.3 internal endpoints for finance-service to fetch owner-service report bytes.")
@RequiredArgsConstructor
public class ScheduledRenderController {

    private static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    private static final String PERM = "scheduled_report:render";

    private final AgedBalancesExcelService agedBalancesService;
    private final UprMovementWorkbookService uprService;
    private final CashFlowForecastExcelService cashFlowService;
    private final SecurityEventPublisher securityEventPublisher;

    @PostMapping(value = "/AGED_DEBTORS/scheduled-render", produces = {
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"})
    @Operation(summary = "Render AGED_DEBTORS workbook for a scheduled fire.")
    @RequiresPermission(PERM)
    public Mono<ResponseEntity<byte[]>> renderAgedDebtors(@RequestBody ScheduledRenderRequest req) {
        log.debug("[scheduled-render] AGED_DEBTORS tenant={} schedule={}",
                req.tenantId(), req.scheduleId());
        return agedBalancesService.generate(req.reportingCurrency(), 30, null)
                .contextWrite(c -> TenantContext.put(c, req.tenantId().toString()))
                .flatMap(bytes -> auditAndWrap(bytes, req, ReportKey.AGED_DEBTORS));
    }

    @PostMapping(value = "/UPR_MOVEMENT/scheduled-render", produces = {
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"})
    @Operation(summary = "Render UPR_MOVEMENT workbook for a scheduled fire.")
    @RequiresPermission(PERM)
    public Mono<ResponseEntity<byte[]>> renderUprMovement(@RequestBody ScheduledRenderRequest req) {
        log.debug("[scheduled-render] UPR_MOVEMENT tenant={} period={}..{}",
                req.tenantId(), req.periodStart(), req.periodEnd());
        return uprService.workbook(req.periodStart(), req.periodEnd(),
                        null, req.reportingCurrency(), req.tenantId())
                .flatMap(bytes -> auditAndWrap(bytes, req, ReportKey.UPR_MOVEMENT));
    }

    @PostMapping(value = "/CASH_FLOW_FORECAST_13W/scheduled-render", produces = {
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"})
    @Operation(summary = "Render CASH_FLOW_FORECAST_13W workbook for a scheduled fire.")
    @RequiresPermission(PERM)
    public Mono<ResponseEntity<byte[]>> renderCashFlow(@RequestBody ScheduledRenderRequest req) {
        log.debug("[scheduled-render] CASH_FLOW_FORECAST_13W tenant={} asOf={}",
                req.tenantId(), req.asOf());
        List<String> warnings = new ArrayList<>();
        return cashFlowService.workbook(req.asOf(), 13, warnings)
                .contextWrite(c -> TenantContext.put(c, req.tenantId().toString()))
                .flatMap(bytes -> auditAndWrap(bytes, req, ReportKey.CASH_FLOW_FORECAST_13W));
    }

    private Mono<ResponseEntity<byte[]>> auditAndWrap(byte[] bytes, ScheduledRenderRequest req,
                                                       ReportKey key) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("source", "SCHEDULED");
        details.put("scheduleId", req.scheduleId() != null ? req.scheduleId().toString() : null);
        details.put("cadence", req.cadenceLabel());
        if (req.periodStart() != null) details.put("periodStart", req.periodStart().toString());
        if (req.periodEnd() != null) details.put("periodEnd", req.periodEnd().toString());
        if (req.asOf() != null) details.put("asOf", req.asOf().toString());
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
