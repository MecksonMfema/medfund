package com.medfund.finance.reinsurance.controller;

import com.medfund.finance.reinsurance.dto.CessionBordereauRow;
import com.medfund.finance.reinsurance.dto.RecoveriesBordereauRow;
import com.medfund.finance.reinsurance.dto.TreatyUtilizationRow;
import com.medfund.finance.reinsurance.repository.RecoveryRepository;
import com.medfund.finance.reinsurance.service.BordereauPeriodExportService;
import com.medfund.finance.reinsurance.service.BordereauReportService;
import com.medfund.finance.reinsurance.service.BordereauReportWorkbookService;
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
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Three bordereau report endpoints — cession, recoveries, treaty
 * utilization. Each GET returns a {@link ReportResponse} envelope (native
 * per-currency subtotals, best-effort FX rates, warnings for missing
 * rates). Each has an XLSX export sibling that additionally records a
 * {@code bordereau_period_export} soft-lock row and emits a
 * {@code DATA_ACCESS} security event.
 *
 * <p>The recoveries export flips affected EXPECTED recoveries →
 * INVOICED <strong>after</strong> the response body is streamed, so a
 * failed render leaves rows in EXPECTED (next export retries) rather
 * than in INVOICED without a delivered workbook (R8 / Phase 4 §5 note).
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/reports/reinsurance")
@RequiredArgsConstructor
@Tag(name = "Reinsurance Reports",
        description = "Cession bordereau, recoveries bordereau, treaty utilization reports "
                    + "(participant-split, native-currency rows, quarter-aligned soft-lock).")
@SecurityRequirement(name = "bearer-jwt")
public class BordereauReportController {

    private static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final BordereauReportService service;
    private final BordereauReportWorkbookService workbookService;
    private final BordereauPeriodExportService exportRegistry;
    private final SecurityEventPublisher securityEventPublisher;
    private final RecoveryRepository recoveryRepository;

    // ── Cession bordereau ───────────────────────────────────────────────────

    @GetMapping("/cession-bordereau")
    @RequiresPermission(Permissions.REINSURANCE_VIEW)
    @RequiresReport(ReportKey.REINSURANCE_CESSION_BORDEREAU)
    @Operation(summary = "Cession bordereau for a quarter",
            description = "Rows are cession-per-participant (R9): one row per (cession, reinsurer). "
                        + "Filter by reinsurerId + treatyId + year + quarter. "
                        + "isPriorPeriodAdjustment flags cessions whose createdAt is after the first "
                        + "export of this quarter's bordereau. Envelope carries per-currency native "
                        + "subtotals + best-effort FX rates to reportingCurrency (override or tenant "
                        + "default).")
    @ApiResponse(responseCode = "200", description = "Envelope wrapping the list of cession-per-participant rows")
    @ApiResponse(responseCode = "403", description = "Report disabled by tenant admin")
    public Mono<ReportResponse<List<CessionBordereauRow>>> cessionBordereau(
            @RequestParam(required = false) UUID reinsurerId,
            @RequestParam(required = false) UUID treatyId,
            @RequestParam int year,
            @RequestParam @Min(1) @Max(4) int quarter,
            @RequestParam(required = false) String reportingCurrency) {
        return service.cessionBordereau(reinsurerId, treatyId, year, quarter, reportingCurrency);
    }

    @GetMapping("/cession-bordereau/export/excel")
    @RequiresPermission(Permissions.REINSURANCE_VIEW)
    @RequiresReport(ReportKey.REINSURANCE_CESSION_BORDEREAU)
    @Operation(summary = "Export cession bordereau as XLSX",
            description = "Multi-sheet: one per currency + Summary with per-currency subtotals + "
                        + "converted grand total. First export inserts a bordereau_period_export "
                        + "row; re-exports increment its counter. Emits DATA_ACCESS security event.")
    public Mono<ResponseEntity<byte[]>> cessionExport(
            @RequestParam(required = false) UUID reinsurerId,
            @RequestParam(required = false) UUID treatyId,
            @RequestParam int year,
            @RequestParam @Min(1) @Max(4) int quarter,
            @RequestParam(required = false) String reportingCurrency,
            @AuthenticationPrincipal Jwt jwt) {
        String actorId    = AuditActor.id(jwt);
        String actorEmail = AuditActor.email(jwt);
        return Mono.deferContextual(ctx -> {
            String tenantIdStr = TenantContext.get(ctx);
            UUID tenantId = parseUuid(tenantIdStr);
            return workbookService
                    .cessionWorkbook(reinsurerId, treatyId, year, quarter, reportingCurrency, tenantId)
                    .flatMap(bytes -> exportRegistry.markExported(reinsurerId, treatyId,
                                    ReportKey.REINSURANCE_CESSION_BORDEREAU.name(),
                                    year, quarter, actorId, actorEmail)
                            .then(securityEventPublisher.publishDataAccess(tenantIdStr, actorId,
                                    actorEmail, ReportKey.REINSURANCE_CESSION_BORDEREAU.name(),
                                    exportDetails(reinsurerId, treatyId, year, quarter, reportingCurrency)))
                            .thenReturn(bytes))
                    .map(bytes -> ResponseEntity.ok()
                            .contentType(XLSX)
                            .header(HttpHeaders.CONTENT_DISPOSITION,
                                    "attachment; filename=\""
                                    + bordereauFilename("cession", reinsurerId, treatyId, year, quarter)
                                    + "\"")
                            .body(bytes));
        });
    }

    // ── Recoveries bordereau ────────────────────────────────────────────────

    @GetMapping("/recoveries-bordereau")
    @RequiresPermission(Permissions.REINSURANCE_VIEW)
    @RequiresReport(ReportKey.REINSURANCE_RECOVERIES)
    @Operation(summary = "Recoveries bordereau for a quarter",
            description = "One row per (recovery, participant) — status ∈ EXPECTED/INVOICED/RECEIVED/"
                        + "WRITTEN_OFF. participantExpected = cession.cededAmount × sharePct / 100; "
                        + "participantReceived = recovery.receivedAmount × sharePct / 100.")
    public Mono<ReportResponse<List<RecoveriesBordereauRow>>> recoveriesBordereau(
            @RequestParam(required = false) UUID reinsurerId,
            @RequestParam(required = false) UUID treatyId,
            @RequestParam int year,
            @RequestParam @Min(1) @Max(4) int quarter,
            @RequestParam(required = false) String reportingCurrency) {
        return service.recoveriesBordereau(reinsurerId, treatyId, year, quarter, reportingCurrency);
    }

    @GetMapping("/recoveries-bordereau/export/excel")
    @RequiresPermission(Permissions.REINSURANCE_VIEW)
    @RequiresReport(ReportKey.REINSURANCE_RECOVERIES)
    @Operation(summary = "Export recoveries bordereau as XLSX",
            description = "EXPECTED recoveries in this quarter transition to INVOICED after the "
                        + "response body is delivered (R8) — a failed render leaves rows in EXPECTED "
                        + "so the next export retries. Bytes-delivery failures are not observable "
                        + "server-side; reinsurers re-request if a bordereau was lost in flight.")
    public Mono<ResponseEntity<byte[]>> recoveriesExport(
            @RequestParam(required = false) UUID reinsurerId,
            @RequestParam(required = false) UUID treatyId,
            @RequestParam int year,
            @RequestParam @Min(1) @Max(4) int quarter,
            @RequestParam(required = false) String reportingCurrency,
            @AuthenticationPrincipal Jwt jwt) {
        String actorId    = AuditActor.id(jwt);
        String actorEmail = AuditActor.email(jwt);
        return Mono.deferContextual(ctx -> {
            String tenantIdStr = TenantContext.get(ctx);
            UUID tenantId = parseUuid(tenantIdStr);
            return workbookService
                    .snapshotExpectedRecoveryCessionIds(reinsurerId, treatyId, year, quarter)
                    .flatMap(cessionIds -> workbookService
                            .recoveriesWorkbook(reinsurerId, treatyId, year, quarter,
                                    reportingCurrency, tenantId)
                            .flatMap(bytes -> exportRegistry.markExported(reinsurerId, treatyId,
                                            ReportKey.REINSURANCE_RECOVERIES.name(),
                                            year, quarter, actorId, actorEmail)
                                    .then(securityEventPublisher.publishDataAccess(tenantIdStr,
                                            actorId, actorEmail,
                                            ReportKey.REINSURANCE_RECOVERIES.name(),
                                            exportDetails(reinsurerId, treatyId, year, quarter,
                                                    reportingCurrency)))
                                    .thenReturn(bytes))
                            .map(bytes -> ResponseEntity.ok()
                                    .contentType(XLSX)
                                    .header(HttpHeaders.CONTENT_DISPOSITION,
                                            "attachment; filename=\""
                                            + bordereauFilename("recoveries", reinsurerId, treatyId,
                                                    year, quarter) + "\"")
                                    .body(bytes))
                            // Post-response: flip snapshot cession IDs EXPECTED → INVOICED (R8).
                            // Fires only on successful body build; a render error skips the flip
                            // so the next export retries the same recoveries.
                            .doOnSuccess(entity -> markInvoicedAsync(cessionIds)
                                    .contextWrite(reactor.util.context.Context.of(
                                            TenantContext.KEY,
                                            tenantIdStr != null ? tenantIdStr : ""))
                                    .subscribe()));
        });
    }

    // ── Treaty utilization ──────────────────────────────────────────────────

    @GetMapping("/treaty-utilization")
    @RequiresPermission(Permissions.REINSURANCE_VIEW)
    @RequiresReport(ReportKey.REINSURANCE_TREATY_UTILIZATION)
    @Operation(summary = "Since-inception utilization for one treaty",
            description = "Per-layer rows for XoL/StopLoss; single logical layer (layerId=null) for "
                        + "QS/SS. totalCededNative + cessionCount per (layer, cededCurrency). "
                        + "asOfDate defaults to today; treatyId is required.")
    public Mono<ReportResponse<List<TreatyUtilizationRow>>> treatyUtilization(
            @RequestParam UUID treatyId,
            @RequestParam(required = false) LocalDate asOfDate,
            @RequestParam(required = false) String reportingCurrency) {
        return service.treatyUtilization(treatyId, asOfDate, reportingCurrency);
    }

    @GetMapping("/treaty-utilization/export/excel")
    @RequiresPermission(Permissions.REINSURANCE_VIEW)
    @RequiresReport(ReportKey.REINSURANCE_TREATY_UTILIZATION)
    @Operation(summary = "Export treaty utilization as XLSX",
            description = "Per-currency sheet plus Summary. Emits DATA_ACCESS security event; "
                        + "no bordereau_period_export row (this is a since-inception snapshot, "
                        + "not a quarter-aligned bordereau).")
    public Mono<ResponseEntity<byte[]>> utilizationExport(
            @RequestParam UUID treatyId,
            @RequestParam(required = false) LocalDate asOfDate,
            @RequestParam(required = false) String reportingCurrency,
            @AuthenticationPrincipal Jwt jwt) {
        String actorId    = AuditActor.id(jwt);
        String actorEmail = AuditActor.email(jwt);
        return Mono.deferContextual(ctx -> {
            String tenantIdStr = TenantContext.get(ctx);
            UUID tenantId = parseUuid(tenantIdStr);
            return workbookService
                    .utilizationWorkbook(treatyId, asOfDate, reportingCurrency, tenantId)
                    .flatMap(bytes -> securityEventPublisher.publishDataAccess(tenantIdStr, actorId,
                                    actorEmail,
                                    ReportKey.REINSURANCE_TREATY_UTILIZATION.name(),
                                    utilizationExportDetails(treatyId, asOfDate, reportingCurrency))
                            .thenReturn(bytes))
                    .map(bytes -> ResponseEntity.ok()
                            .contentType(XLSX)
                            .header(HttpHeaders.CONTENT_DISPOSITION,
                                    "attachment; filename=\""
                                    + utilizationFilename(treatyId, asOfDate) + "\"")
                            .body(bytes));
        });
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private Mono<Void> markInvoicedAsync(List<UUID> cessionIds) {
        if (cessionIds == null || cessionIds.isEmpty()) return Mono.empty();
        return recoveryRepository
                .markInvoicedByCessionIds(cessionIds, OffsetDateTime.now())
                .doOnError(err -> log.error(
                        "[recoveries-bordereau] post-response markInvoiced failed for {} cessions: ",
                        cessionIds.size(), err))
                .onErrorResume(err -> Mono.empty())
                .then();
    }

    private static UUID parseUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
    }

    private static Map<String, Object> exportDetails(UUID reinsurerId, UUID treatyId,
                                                     int year, int quarter,
                                                     String reportingCurrency) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("year", year);
        details.put("quarter", quarter);
        if (reinsurerId != null)               details.put("reinsurerId", reinsurerId.toString());
        if (treatyId != null)                  details.put("treatyId",    treatyId.toString());
        if (reportingCurrency != null
                && !reportingCurrency.isBlank()) details.put("reportingCurrency", reportingCurrency);
        return details;
    }

    private static Map<String, Object> utilizationExportDetails(UUID treatyId, LocalDate asOfDate,
                                                                String reportingCurrency) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("treatyId", treatyId.toString());
        details.put("asOfDate", asOfDate != null ? asOfDate.toString() : LocalDate.now().toString());
        if (reportingCurrency != null
                && !reportingCurrency.isBlank()) details.put("reportingCurrency", reportingCurrency);
        return details;
    }

    private static String bordereauFilename(String kind, UUID reinsurerId, UUID treatyId,
                                            int year, int quarter) {
        StringBuilder sb = new StringBuilder("reinsurance-").append(kind)
                .append("-bordereau-").append(year).append("-Q").append(quarter);
        if (reinsurerId != null) sb.append("-r").append(reinsurerId.toString().substring(0, 8));
        if (treatyId != null)    sb.append("-t").append(treatyId.toString().substring(0, 8));
        return sb.append(".xlsx").toString();
    }

    private static String utilizationFilename(UUID treatyId, LocalDate asOfDate) {
        String date = (asOfDate != null ? asOfDate : LocalDate.now()).toString();
        return "reinsurance-treaty-utilization-" + treatyId.toString().substring(0, 8) + "-" + date + ".xlsx";
    }
}
