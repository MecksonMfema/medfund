package com.medfund.user.report.schedule;

import com.medfund.shared.report.ReportKey;
import com.medfund.shared.report.ScheduledRenderRequest;
import com.medfund.shared.security.RequiresPermission;
import com.medfund.shared.security.SecurityEventPublisher;
import com.medfund.shared.tenant.TenantContext;
import com.medfund.user.reports.lifecycle.service.GroupCensusWorkbookService;
import com.medfund.user.reports.lifecycle.service.PersistencyCohortWorkbookService;
import com.medfund.user.reports.lifecycle.service.PolicyMovementWorkbookService;
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
 * Phase 17 §A.3 — scheduled-render endpoints for user-service-owned
 * cadenced keys (POLICY_MOVEMENT, PERSISTENCY_COHORT, GROUP_CENSUS).
 * All three shape services resolve the tenant reactively; the controller
 * plants the tenant on the reactor context before delegating.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/reports")
@Tag(name = "Scheduled report render",
        description = "Phase 17 §A.3 internal endpoints for finance-service to fetch user report bytes.")
@RequiredArgsConstructor
public class ScheduledRenderController {

    private static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    private static final String PERM = "scheduled_report:render";

    private final PolicyMovementWorkbookService policyMovementService;
    private final PersistencyCohortWorkbookService persistencyCohortService;
    private final GroupCensusWorkbookService groupCensusService;
    private final SecurityEventPublisher securityEventPublisher;

    @PostMapping(value = "/POLICY_MOVEMENT/scheduled-render", produces = {
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"})
    @Operation(summary = "Render POLICY_MOVEMENT workbook for a scheduled fire.")
    @RequiresPermission(PERM)
    public Mono<ResponseEntity<byte[]>> renderPolicyMovement(@RequestBody ScheduledRenderRequest req) {
        log.debug("[scheduled-render] POLICY_MOVEMENT tenant={} period={}..{}",
                req.tenantId(), req.periodStart(), req.periodEnd());
        return policyMovementService.workbook(
                        req.periodStart(), req.periodEnd(), req.reportingCurrency())
                .contextWrite(c -> TenantContext.put(c, req.tenantId().toString()))
                .flatMap(bytes -> auditAndWrap(bytes, req, ReportKey.POLICY_MOVEMENT));
    }

    @PostMapping(value = "/PERSISTENCY_COHORT/scheduled-render", produces = {
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"})
    @Operation(summary = "Render PERSISTENCY_COHORT workbook for a scheduled fire.")
    @RequiresPermission(PERM)
    public Mono<ResponseEntity<byte[]>> renderPersistencyCohort(@RequestBody ScheduledRenderRequest req) {
        log.debug("[scheduled-render] PERSISTENCY_COHORT tenant={} period={}..{}",
                req.tenantId(), req.periodStart(), req.periodEnd());
        return persistencyCohortService.workbook(
                        req.periodStart(), req.periodEnd(), null, null, req.reportingCurrency())
                .contextWrite(c -> TenantContext.put(c, req.tenantId().toString()))
                .flatMap(bytes -> auditAndWrap(bytes, req, ReportKey.PERSISTENCY_COHORT));
    }

    @PostMapping(value = "/GROUP_CENSUS/scheduled-render", produces = {
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"})
    @Operation(summary = "Render GROUP_CENSUS workbook for a scheduled fire.")
    @RequiresPermission(PERM)
    public Mono<ResponseEntity<byte[]>> renderGroupCensus(@RequestBody ScheduledRenderRequest req) {
        log.debug("[scheduled-render] GROUP_CENSUS tenant={} asOf={}",
                req.tenantId(), req.asOf());
        return groupCensusService.workbook(req.asOf(), null, null, req.reportingCurrency())
                .contextWrite(c -> TenantContext.put(c, req.tenantId().toString()))
                .flatMap(bytes -> auditAndWrap(bytes, req, ReportKey.GROUP_CENSUS));
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
