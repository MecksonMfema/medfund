package com.medfund.user.reports.lifecycle.controller;

import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.report.ReportKey;
import com.medfund.shared.report.ReportResponse;
import com.medfund.shared.report.RequiresReport;
import com.medfund.shared.security.Permissions;
import com.medfund.shared.security.RequiresPermission;
import com.medfund.shared.security.SecurityEventPublisher;
import com.medfund.shared.tenant.TenantContext;
import com.medfund.user.reports.lifecycle.dto.GroupCensusResult;
import com.medfund.user.reports.lifecycle.dto.PersistencyCohortResult;
import com.medfund.user.reports.lifecycle.dto.PolicyMovementResult;
import com.medfund.user.reports.lifecycle.service.GroupCensusReportService;
import com.medfund.user.reports.lifecycle.service.GroupCensusWorkbookService;
import com.medfund.user.reports.lifecycle.service.PersistencyCohortReportService;
import com.medfund.user.reports.lifecycle.service.PersistencyCohortWorkbookService;
import com.medfund.user.reports.lifecycle.service.PolicyMovementReportService;
import com.medfund.user.reports.lifecycle.service.PolicyMovementWorkbookService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 13 §C Phase 8: POLICY_LIFECYCLE report endpoints — POLICY_MOVEMENT,
 * PERSISTENCY_COHORT, GROUP_CENSUS. Each report gets a JSON GET and an XLSX
 * export sibling. Export emits {@code DATA_ACCESS} security events with
 * the report key + filters per parent-plan invariant #3.
 */
@RestController
@RequestMapping("/api/v1/reports/policy-lifecycle")
@RequiredArgsConstructor
@Tag(name = "Policy lifecycle reports",
        description = "Phase 13 §C: POLICY_MOVEMENT + PERSISTENCY_COHORT + GROUP_CENSUS. "
                + "Every GET is gated by @RequiresReport (tenant admin toggle) and every "
                + "XLSX export emits a DATA_ACCESS security event.")
@SecurityRequirement(name = "bearer-jwt")
public class PolicyLifecycleReportController {

    private static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final PolicyMovementReportService movementService;
    private final PolicyMovementWorkbookService movementWorkbook;
    private final PersistencyCohortReportService persistencyService;
    private final PersistencyCohortWorkbookService persistencyWorkbook;
    private final GroupCensusReportService censusService;
    private final GroupCensusWorkbookService censusWorkbook;
    private final SecurityEventPublisher securityEventPublisher;

    // ── POLICY_MOVEMENT ─────────────────────────────────────────────────

    @GetMapping("/movement")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.POLICY_MOVEMENT)
    @Operation(summary = "Policy movement for a window",
            description = "One row per (policy_source, currency) with opening/added/renewed/"
                    + "lapsed/terminated/closing counts + |written_premium| roll (native).")
    @ApiResponse(responseCode = "200", description = "Envelope wrapping PolicyMovementResult")
    @ApiResponse(responseCode = "403", description = "Report disabled by tenant admin")
    public Mono<ReportResponse<PolicyMovementResult>> movement(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodStart,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodEnd,
            @RequestParam(required = false) String reportingCurrency) {
        return movementService.generate(periodStart, periodEnd, reportingCurrency);
    }

    @GetMapping("/movement/export")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.POLICY_MOVEMENT)
    @Operation(summary = "Export policy movement as XLSX",
            description = "Movement + Summary sheets. Emits DATA_ACCESS security event before returning bytes.")
    public Mono<ResponseEntity<byte[]>> movementExport(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodStart,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodEnd,
            @RequestParam(required = false) String reportingCurrency,
            @AuthenticationPrincipal Jwt jwt) {
        return xlsxResponse(
                movementWorkbook.workbook(periodStart, periodEnd, reportingCurrency),
                ReportKey.POLICY_MOVEMENT,
                movementDetails(periodStart, periodEnd, reportingCurrency),
                "policy-movement-" + periodStart + "_" + periodEnd + ".xlsx",
                jwt);
    }

    // ── PERSISTENCY_COHORT ──────────────────────────────────────────────

    @GetMapping("/persistency-cohort")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.PERSISTENCY_COHORT)
    @Operation(summary = "Persistency cohort study",
            description = "One row per (cohort_month, line, checkpoint). HEALTH uses the "
                    + "member_contribution_presence matview; annual lines use renewal-chain-active.")
    public Mono<ReportResponse<PersistencyCohortResult>> persistency(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodStart,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodEnd,
            @RequestParam(required = false) String checkpoints,
            @RequestParam(required = false) String insuranceLine,
            @RequestParam(required = false) String reportingCurrency) {
        return persistencyService.generate(periodStart, periodEnd,
                parseCheckpoints(checkpoints), insuranceLine, reportingCurrency);
    }

    @GetMapping("/persistency-cohort/export")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.PERSISTENCY_COHORT)
    @Operation(summary = "Export persistency cohort as XLSX")
    public Mono<ResponseEntity<byte[]>> persistencyExport(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodStart,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodEnd,
            @RequestParam(required = false) String checkpoints,
            @RequestParam(required = false) String insuranceLine,
            @RequestParam(required = false) String reportingCurrency,
            @AuthenticationPrincipal Jwt jwt) {
        List<Integer> parsed = parseCheckpoints(checkpoints);
        Map<String, Object> details = persistencyDetails(periodStart, periodEnd, checkpoints,
                insuranceLine, reportingCurrency);
        return xlsxResponse(
                persistencyWorkbook.workbook(periodStart, periodEnd, parsed, insuranceLine, reportingCurrency),
                ReportKey.PERSISTENCY_COHORT,
                details,
                "persistency-cohort-" + periodStart + "_" + periodEnd + ".xlsx",
                jwt);
    }

    // ── GROUP_CENSUS ────────────────────────────────────────────────────

    @GetMapping("/group-census")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.GROUP_CENSUS)
    @Operation(summary = "Group census snapshot",
            description = "One row per group at asOf with per-status member counts. "
                    + "asOf defaults to today; a future asOf is rejected with 400.")
    public Mono<ReportResponse<GroupCensusResult>> census(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
            @RequestParam(required = false) UUID groupId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String reportingCurrency) {
        LocalDate effective = requireAsOfSaneOrToday(asOf);
        return censusService.generate(effective, groupId, status, reportingCurrency);
    }

    @GetMapping("/group-census/export")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.GROUP_CENSUS)
    @Operation(summary = "Export group census as XLSX")
    public Mono<ResponseEntity<byte[]>> censusExport(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
            @RequestParam(required = false) UUID groupId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String reportingCurrency,
            @AuthenticationPrincipal Jwt jwt) {
        LocalDate effective = requireAsOfSaneOrToday(asOf);
        Map<String, Object> details = censusDetails(effective, groupId, status, reportingCurrency);
        return xlsxResponse(
                censusWorkbook.workbook(effective, groupId, status, reportingCurrency),
                ReportKey.GROUP_CENSUS,
                details,
                "group-census-" + effective + ".xlsx",
                jwt);
    }

    // ── Shared plumbing ─────────────────────────────────────────────────

    private Mono<ResponseEntity<byte[]>> xlsxResponse(Mono<byte[]> bytesMono, ReportKey key,
                                                     Map<String, Object> details, String filename,
                                                     Jwt jwt) {
        String actorId = AuditActor.id(jwt);
        String actorEmail = AuditActor.email(jwt);
        return Mono.deferContextual(ctx -> {
            String tenantIdStr = TenantContext.get(ctx);
            return bytesMono
                    .flatMap(bytes -> securityEventPublisher
                            .publishDataAccess(tenantIdStr, actorId, actorEmail, key.name(), details)
                            .thenReturn(bytes))
                    .map(bytes -> ResponseEntity.ok()
                            .contentType(XLSX)
                            .header(HttpHeaders.CONTENT_DISPOSITION,
                                    "attachment; filename=\"" + filename + "\"")
                            .body(bytes));
        });
    }

    private static List<Integer> parseCheckpoints(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        List<Integer> out = new ArrayList<>();
        for (String s : csv.split(",")) {
            String trimmed = s.trim();
            if (trimmed.isEmpty()) continue;
            try {
                int val = Integer.parseInt(trimmed);
                if (val > 0) out.add(val);
            } catch (NumberFormatException ex) {
                throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
                        "Invalid checkpoint value: " + trimmed);
            }
        }
        return out;
    }

    private static LocalDate requireAsOfSaneOrToday(LocalDate asOf) {
        LocalDate effective = asOf != null ? asOf : LocalDate.now();
        if (effective.isAfter(LocalDate.now())) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "asOf cannot be in the future");
        }
        return effective;
    }

    private static Map<String, Object> movementDetails(LocalDate ps, LocalDate pe, String rc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("periodStart", ps.toString());
        m.put("periodEnd",   pe.toString());
        if (rc != null && !rc.isBlank()) m.put("reportingCurrency", rc);
        return m;
    }

    private static Map<String, Object> persistencyDetails(LocalDate ps, LocalDate pe, String cp,
                                                         String line, String rc) {
        Map<String, Object> m = movementDetails(ps, pe, rc);
        if (cp != null && !cp.isBlank())     m.put("checkpoints", cp);
        if (line != null && !line.isBlank()) m.put("insuranceLine", line);
        return m;
    }

    private static Map<String, Object> censusDetails(LocalDate asOf, UUID groupId, String status, String rc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("asOf", asOf.toString());
        if (groupId != null)                   m.put("groupId", groupId.toString());
        if (status != null && !status.isBlank()) m.put("status", status);
        if (rc != null && !rc.isBlank())        m.put("reportingCurrency", rc);
        return m;
    }
}
