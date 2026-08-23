package com.medfund.contributions.premium.controller;

import com.medfund.contributions.premium.dto.NewBusinessRegisterRow;
import com.medfund.contributions.premium.dto.PremiumRegisterRow;
import com.medfund.contributions.premium.dto.UprMovementRow;
import com.medfund.contributions.premium.service.NewBusinessRegisterReportService;
import com.medfund.contributions.premium.service.NewBusinessRegisterWorkbookService;
import com.medfund.contributions.premium.service.PremiumRegisterReportService;
import com.medfund.contributions.premium.service.PremiumRegisterWorkbookService;
import com.medfund.contributions.premium.service.UprMovementReportService;
import com.medfund.contributions.premium.service.UprMovementWorkbookService;
import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.report.ReportKey;
import com.medfund.shared.report.ReportResponse;
import com.medfund.shared.report.RequiresReport;
import com.medfund.shared.security.Permissions;
import com.medfund.shared.security.RequiresPermission;
import com.medfund.shared.security.SecurityEventPublisher;
import com.medfund.shared.tenant.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 12 §B underwriting-report endpoints — UPR Movement, Premium
 * Register, New Business Register. Every JSON GET short-circuits with
 * HTTP 403 when the tenant admin has disabled the report via
 * {@code public.tenant_report_config} (through {@link RequiresReport}).
 * Every XLSX export additionally emits a {@code DATA_ACCESS} security
 * event carrying the report key + filters before returning the workbook
 * bytes (parent-plan invariant #3).
 *
 * <p>Rows in the JSON envelope stay native-currency (parent-plan
 * invariant #1); the {@code perCurrency} + {@code fxRates} on
 * {@link ReportResponse} carry the metadata a client uses to convert.
 * The XLSX Summary sheet includes a best-effort converted grand total
 * (or a warnings line when historical FX for a currency is unavailable).
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/reports/premium")
@RequiredArgsConstructor
@Tag(name = "Underwriting reports",
        description = "Phase 12 §B — UPR movement, premium register, new business register. "
                + "Native-currency rows, best-effort FX to reporting currency, XLSX exports "
                + "with security-event trail.")
@SecurityRequirement(name = "bearer-jwt")
public class PremiumReportController {

    private static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final UprMovementReportService uprService;
    private final UprMovementWorkbookService uprWorkbook;
    private final PremiumRegisterReportService registerService;
    private final PremiumRegisterWorkbookService registerWorkbook;
    private final NewBusinessRegisterReportService newBusinessService;
    private final NewBusinessRegisterWorkbookService newBusinessWorkbook;
    private final SecurityEventPublisher securityEventPublisher;

    // ── UPR Movement ────────────────────────────────────────────────────────

    @GetMapping("/upr-movement")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.UPR_MOVEMENT)
    @Operation(summary = "UPR movement for a period",
            description = "One row per (insurance_line, currency) with opening UPR, written premium, "
                    + "earned premium, endorsement delta, and closing UPR — reconciled by the SQL. "
                    + "Optional insuranceLine filter narrows to a single line; optional "
                    + "reportingCurrency overrides the tenant default. Envelope carries per-currency "
                    + "native subtotals + best-effort FX to reportingCurrency.")
    @ApiResponse(responseCode = "200", description = "Envelope wrapping the UPR movement rows")
    @ApiResponse(responseCode = "403", description = "Report disabled by tenant admin")
    public Mono<ReportResponse<List<UprMovementRow>>> uprMovement(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodStart,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodEnd,
            @RequestParam(required = false) String insuranceLine,
            @RequestParam(required = false) String reportingCurrency) {
        return uprService.movement(periodStart, periodEnd, insuranceLine, reportingCurrency);
    }

    @GetMapping("/upr-movement/export/excel")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.UPR_MOVEMENT)
    @Operation(summary = "Export UPR movement as XLSX",
            description = "One sheet per insurance line + Summary with per-currency closing UPR and "
                    + "best-effort converted grand total. Emits DATA_ACCESS security event.")
    public Mono<ResponseEntity<byte[]>> uprMovementExport(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodStart,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodEnd,
            @RequestParam(required = false) String insuranceLine,
            @RequestParam(required = false) String reportingCurrency,
            @AuthenticationPrincipal Jwt jwt) {
        return exportExcel(jwt, ReportKey.UPR_MOVEMENT,
                filename("upr-movement", periodStart, periodEnd, insuranceLine),
                exportDetails(periodStart, periodEnd, insuranceLine, reportingCurrency),
                tenantId -> uprWorkbook.workbook(periodStart, periodEnd, insuranceLine,
                        reportingCurrency, tenantId));
    }

    // ── Premium Register ────────────────────────────────────────────────────

    @GetMapping("/register")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.PREMIUM_REGISTER)
    @Operation(summary = "Premium register for a period",
            description = "One row per (policy, period) whose earning_schedule row overlaps the "
                    + "reporting window. Enriched with member + scheme names and IFRS 17 portfolio + "
                    + "cohort labels.")
    @ApiResponse(responseCode = "200", description = "Envelope wrapping the premium register rows")
    @ApiResponse(responseCode = "403", description = "Report disabled by tenant admin")
    public Mono<ReportResponse<List<PremiumRegisterRow>>> premiumRegister(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodStart,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodEnd,
            @RequestParam(required = false) String insuranceLine,
            @RequestParam(required = false) String reportingCurrency) {
        return registerService.register(periodStart, periodEnd, insuranceLine, reportingCurrency);
    }

    @GetMapping("/register/export/excel")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.PREMIUM_REGISTER)
    @Operation(summary = "Export premium register as XLSX",
            description = "Single Register sheet + Summary. Emits DATA_ACCESS security event.")
    public Mono<ResponseEntity<byte[]>> premiumRegisterExport(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodStart,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodEnd,
            @RequestParam(required = false) String insuranceLine,
            @RequestParam(required = false) String reportingCurrency,
            @AuthenticationPrincipal Jwt jwt) {
        return exportExcel(jwt, ReportKey.PREMIUM_REGISTER,
                filename("premium-register", periodStart, periodEnd, insuranceLine),
                exportDetails(periodStart, periodEnd, insuranceLine, reportingCurrency),
                tenantId -> registerWorkbook.workbook(periodStart, periodEnd, insuranceLine,
                        reportingCurrency, tenantId));
    }

    // ── New Business Register ───────────────────────────────────────────────

    @GetMapping("/new-business")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.NEW_BUSINESS_REGISTER)
    @Operation(summary = "New business register for a period",
            description = "One row per policy (or HEALTH member's first Contribution) that first "
                    + "bound within the window. Annual-bind lines are filtered by "
                    + "renewed_from_policy_id IS NULL AND bound_at ∈ [periodStart, periodEnd]; HEALTH "
                    + "uses the member_first_contribution materialised view.")
    @ApiResponse(responseCode = "200", description = "Envelope wrapping the new-business rows")
    @ApiResponse(responseCode = "403", description = "Report disabled by tenant admin")
    public Mono<ReportResponse<List<NewBusinessRegisterRow>>> newBusiness(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodStart,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodEnd,
            @RequestParam(required = false) String insuranceLine,
            @RequestParam(required = false) String reportingCurrency) {
        return newBusinessService.register(periodStart, periodEnd, insuranceLine, reportingCurrency);
    }

    @GetMapping("/new-business/export/excel")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.NEW_BUSINESS_REGISTER)
    @Operation(summary = "Export new business register as XLSX",
            description = "Single New Business sheet + Summary. Emits DATA_ACCESS security event.")
    public Mono<ResponseEntity<byte[]>> newBusinessExport(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodStart,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodEnd,
            @RequestParam(required = false) String insuranceLine,
            @RequestParam(required = false) String reportingCurrency,
            @AuthenticationPrincipal Jwt jwt) {
        return exportExcel(jwt, ReportKey.NEW_BUSINESS_REGISTER,
                filename("new-business-register", periodStart, periodEnd, insuranceLine),
                exportDetails(periodStart, periodEnd, insuranceLine, reportingCurrency),
                tenantId -> newBusinessWorkbook.workbook(periodStart, periodEnd, insuranceLine,
                        reportingCurrency, tenantId));
    }

    // ── Export helper ───────────────────────────────────────────────────────

    private Mono<ResponseEntity<byte[]>> exportExcel(
            Jwt jwt, ReportKey key, String filename, Map<String, Object> details,
            java.util.function.Function<UUID, Mono<byte[]>> workbookFn) {
        String actorId = AuditActor.id(jwt);
        String actorEmail = AuditActor.email(jwt);
        return Mono.deferContextual(ctx -> {
            String tenantIdStr = TenantContext.get(ctx);
            UUID tenantId = parseUuid(tenantIdStr);
            return workbookFn.apply(tenantId)
                    .flatMap(bytes -> securityEventPublisher.publishDataAccess(tenantIdStr, actorId,
                                    actorEmail, key.name(), details)
                            .thenReturn(bytes))
                    .map(bytes -> ResponseEntity.ok()
                            .contentType(XLSX)
                            .header(HttpHeaders.CONTENT_DISPOSITION,
                                    "attachment; filename=\"" + filename + "\"")
                            .body(bytes));
        });
    }

    private static Map<String, Object> exportDetails(LocalDate periodStart, LocalDate periodEnd,
                                                    String insuranceLine, String reportingCurrency) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("periodStart", periodStart.toString());
        details.put("periodEnd", periodEnd.toString());
        if (insuranceLine != null && !insuranceLine.isBlank()) details.put("insuranceLine", insuranceLine);
        if (reportingCurrency != null && !reportingCurrency.isBlank())
            details.put("reportingCurrency", reportingCurrency);
        return details;
    }

    private static String filename(String slug, LocalDate start, LocalDate end, String insuranceLine) {
        StringBuilder sb = new StringBuilder(slug)
                .append("-").append(start).append("_").append(end);
        if (insuranceLine != null && !insuranceLine.isBlank()) sb.append("-").append(insuranceLine.toLowerCase());
        return sb.append(".xlsx").toString();
    }

    private static UUID parseUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
    }
}
