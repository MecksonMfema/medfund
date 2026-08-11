package com.medfund.contributions.controller;

import com.medfund.contributions.dto.PageResponse;
import com.medfund.contributions.dto.ReceiptsDetailResponse;
import com.medfund.contributions.dto.ReceiptsSummaryRow;
import com.medfund.contributions.service.ReceiptsExcelService;
import com.medfund.contributions.service.ReceiptsReportService;
import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.report.ReportEnvelopeBuilder;
import com.medfund.shared.report.ReportKey;
import com.medfund.shared.report.ReportPeriod;
import com.medfund.shared.report.ReportResponse;
import com.medfund.shared.report.RequiresReport;
import com.medfund.shared.security.Permissions;
import com.medfund.shared.security.RequiresPermission;
import com.medfund.shared.security.SecurityEventPublisher;
import com.medfund.shared.tenant.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 3 receipts-report family — per-scheme / per-group / per-member
 * summaries plus drill-down detail for each dimension. Every read is
 * wrapped in {@link ReportResponse} via {@link ReportEnvelopeBuilder};
 * every export fires a {@code DATA_ACCESS} {@code SecurityEvent} before
 * returning bytes.
 *
 * <p>Receipt netting per {@code transaction_types.sign} (F25); the
 * {@code <UNALLOCATED>} synthetic scheme bucket captures group-owned
 * transactions without a {@code contribution_id} back-link (G33).
 *
 * <p>Reports are gated by {@link RequiresReport(ReportKey#RECEIPTS_REPORT)}
 * (summary) or {@link RequiresReport(ReportKey#RECEIPTS_AGGREGATE)}
 * (drill-down). Cross-service aggregate paths live on
 * {@link ReceiptsAggregateController}, ungated.
 */
@RestController
@RequestMapping("/api/v1/reports/receipts")
@RequiredArgsConstructor
@Tag(name = "Receipts reports",
        description = "Phase 3 receipts-report family — per-scheme / per-group / per-member summaries "
                    + "plus per-dimension drill-down. Amounts are net-received (payments in, minus "
                    + "refunds/reversals) per the transaction_types.sign catalogue.")
@SecurityRequirement(name = "bearer-jwt")
public class ReceiptsReportController {

    private static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final ReceiptsReportService receiptsReportService;
    private final ReceiptsExcelService receiptsExcelService;
    private final ReportEnvelopeBuilder envelopeBuilder;
    private final SecurityEventPublisher securityEventPublisher;

    // ── Per-scheme ─────────────────────────────────────────────────────────

    @GetMapping("/schemes")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.RECEIPTS_REPORT)
    @Operation(summary = "Per-scheme receipts aggregate for the window",
            description = "One row per (scheme, currency). Group-owned transactions without a "
                        + "contribution_id back-link land in a synthetic 'Unallocated group payments' "
                        + "scheme row (dimensionId=null).")
    public Mono<ReportResponse<List<ReceiptsSummaryRow>>> schemesReport(
            @Parameter(description = "ISO date — first day of the reporting window (inclusive)")
            @RequestParam String periodStart,
            @Parameter(description = "ISO date — last day of the reporting window (inclusive)")
            @RequestParam String periodEnd,
            @Parameter(description = "Optional ISO-4217 override; defaults to the tenant's default currency")
            @RequestParam(required = false) String reportingCurrency) {
        ReportPeriod period = ReportPeriod.parseFromQueryParams(periodStart, periodEnd, null);
        return envelopeBuilder.build(
                ReportKey.RECEIPTS_REPORT,
                period,
                reportingCurrency,
                receiptsReportService.perSchemeSummary(period.periodStart(), period.periodEnd()),
                receiptsReportService.perSchemePerCurrencyTotals(period.periodStart(), period.periodEnd()));
    }

    @GetMapping("/schemes/export/excel")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.RECEIPTS_REPORT)
    @Operation(summary = "Download the per-scheme receipts report as XLSX")
    public Mono<ResponseEntity<byte[]>> exportSchemesExcel(
            @RequestParam String periodStart,
            @RequestParam String periodEnd,
            @RequestParam(required = false) String reportingCurrency,
            @AuthenticationPrincipal Jwt jwt) {
        ReportPeriod period = ReportPeriod.parseFromQueryParams(periodStart, periodEnd, null);
        String filename = "receipts-schemes-" + period.periodStart() + "-to-" + period.periodEnd() + ".xlsx";
        return receiptsExcelService.schemesReportExcel(period.periodStart(), period.periodEnd(), reportingCurrency)
                .flatMap(bytes -> publishExportEvent(ReportKey.RECEIPTS_REPORT, period, reportingCurrency,
                        Map.of(), jwt).thenReturn(bytes))
                .map(bytes -> attach(bytes, filename));
    }

    @GetMapping("/schemes/{schemeId}")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.RECEIPTS_AGGREGATE)
    @Operation(summary = "Single-scheme receipts drill-down with monthly buckets + ledger",
            description = "Passing `unallocated=true` returns the synthetic bucket for group-owned "
                        + "transactions without a contribution back-link; in that mode the "
                        + "{schemeId} path segment is ignored.")
    public Mono<ReportResponse<ReceiptsDetailResponse>> schemeDetail(
            @PathVariable UUID schemeId,
            @RequestParam String periodStart,
            @RequestParam String periodEnd,
            @RequestParam(required = false) String transactionType,
            @RequestParam(required = false) String currency,
            @RequestParam(required = false) String reportingCurrency,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(defaultValue = "false") boolean unallocated) {
        ReportPeriod period = ReportPeriod.parseFromQueryParams(periodStart, periodEnd, null);
        Mono<ReceiptsDetailResponse> data = unallocated
                ? receiptsReportService.unallocatedDetail(period.periodStart(), period.periodEnd(),
                        transactionType, currency, page, size)
                : receiptsReportService.detail("SCHEME", schemeId, period.periodStart(), period.periodEnd(),
                        transactionType, currency, page, size);
        return envelopeBuilder.buildNoAggregate(ReportKey.RECEIPTS_AGGREGATE, period, reportingCurrency, data);
    }

    @GetMapping("/schemes/{schemeId}/export/excel")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.RECEIPTS_AGGREGATE)
    @Operation(summary = "Download the scheme receipts detail as a two-sheet XLSX")
    public Mono<ResponseEntity<byte[]>> exportSchemeDetailExcel(
            @PathVariable UUID schemeId,
            @RequestParam String periodStart,
            @RequestParam String periodEnd,
            @RequestParam(required = false) String transactionType,
            @RequestParam(required = false) String currency,
            @RequestParam(required = false) String reportingCurrency,
            @RequestParam(defaultValue = "false") boolean unallocated,
            @AuthenticationPrincipal Jwt jwt) {
        ReportPeriod period = ReportPeriod.parseFromQueryParams(periodStart, periodEnd, null);
        String filename = "receipts-" + (unallocated ? "unallocated" : "scheme-" + schemeId)
                + "-" + period.periodStart() + "-to-" + period.periodEnd() + ".xlsx";
        Mono<byte[]> workbook = unallocated
                ? receiptsExcelService.unallocatedDetailExcel(period.periodStart(), period.periodEnd(),
                        transactionType, currency, reportingCurrency)
                : receiptsExcelService.detailExcel("SCHEME", schemeId, "Scheme",
                        period.periodStart(), period.periodEnd(),
                        transactionType, currency, reportingCurrency);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("dimension", unallocated ? "UNALLOCATED" : "SCHEME");
        if (!unallocated) details.put("dimensionId", schemeId.toString());
        if (transactionType != null) details.put("transactionType", transactionType);
        if (currency != null) details.put("currency", currency);
        return workbook
                .flatMap(bytes -> publishExportEvent(ReportKey.RECEIPTS_AGGREGATE, period, reportingCurrency,
                        details, jwt).thenReturn(bytes))
                .map(bytes -> attach(bytes, filename));
    }

    // ── Per-group ──────────────────────────────────────────────────────────

    @GetMapping("/groups")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.RECEIPTS_REPORT)
    @Operation(summary = "Per-group receipts aggregate for the window",
            description = "One row per (group, currency). Ungrouped members' receipts appear on the "
                        + "per-member surface, not here.")
    public Mono<ReportResponse<List<ReceiptsSummaryRow>>> groupsReport(
            @RequestParam String periodStart,
            @RequestParam String periodEnd,
            @RequestParam(required = false) String reportingCurrency) {
        ReportPeriod period = ReportPeriod.parseFromQueryParams(periodStart, periodEnd, null);
        return envelopeBuilder.build(
                ReportKey.RECEIPTS_REPORT,
                period,
                reportingCurrency,
                receiptsReportService.perGroupSummary(period.periodStart(), period.periodEnd()),
                receiptsReportService.perGroupPerCurrencyTotals(period.periodStart(), period.periodEnd()));
    }

    @GetMapping("/groups/export/excel")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.RECEIPTS_REPORT)
    @Operation(summary = "Download the per-group receipts report as XLSX")
    public Mono<ResponseEntity<byte[]>> exportGroupsExcel(
            @RequestParam String periodStart,
            @RequestParam String periodEnd,
            @RequestParam(required = false) String reportingCurrency,
            @AuthenticationPrincipal Jwt jwt) {
        ReportPeriod period = ReportPeriod.parseFromQueryParams(periodStart, periodEnd, null);
        String filename = "receipts-groups-" + period.periodStart() + "-to-" + period.periodEnd() + ".xlsx";
        return receiptsExcelService.groupsReportExcel(period.periodStart(), period.periodEnd(), reportingCurrency)
                .flatMap(bytes -> publishExportEvent(ReportKey.RECEIPTS_REPORT, period, reportingCurrency,
                        Map.of(), jwt).thenReturn(bytes))
                .map(bytes -> attach(bytes, filename));
    }

    @GetMapping("/groups/{groupId}")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.RECEIPTS_AGGREGATE)
    @Operation(summary = "Single-group receipts drill-down")
    public Mono<ReportResponse<ReceiptsDetailResponse>> groupDetail(
            @PathVariable UUID groupId,
            @RequestParam String periodStart,
            @RequestParam String periodEnd,
            @RequestParam(required = false) String transactionType,
            @RequestParam(required = false) String currency,
            @RequestParam(required = false) String reportingCurrency,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        ReportPeriod period = ReportPeriod.parseFromQueryParams(periodStart, periodEnd, null);
        return envelopeBuilder.buildNoAggregate(
                ReportKey.RECEIPTS_AGGREGATE,
                period,
                reportingCurrency,
                receiptsReportService.detail("GROUP", groupId, period.periodStart(), period.periodEnd(),
                        transactionType, currency, page, size));
    }

    @GetMapping("/groups/{groupId}/export/excel")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.RECEIPTS_AGGREGATE)
    @Operation(summary = "Download the group receipts detail as a two-sheet XLSX")
    public Mono<ResponseEntity<byte[]>> exportGroupDetailExcel(
            @PathVariable UUID groupId,
            @RequestParam String periodStart,
            @RequestParam String periodEnd,
            @RequestParam(required = false) String transactionType,
            @RequestParam(required = false) String currency,
            @RequestParam(required = false) String reportingCurrency,
            @AuthenticationPrincipal Jwt jwt) {
        ReportPeriod period = ReportPeriod.parseFromQueryParams(periodStart, periodEnd, null);
        String filename = "receipts-group-" + groupId + "-" + period.periodStart() + "-to-" + period.periodEnd() + ".xlsx";
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("dimension", "GROUP");
        details.put("dimensionId", groupId.toString());
        if (transactionType != null) details.put("transactionType", transactionType);
        if (currency != null) details.put("currency", currency);
        return receiptsExcelService.detailExcel("GROUP", groupId, "Group",
                        period.periodStart(), period.periodEnd(), transactionType, currency, reportingCurrency)
                .flatMap(bytes -> publishExportEvent(ReportKey.RECEIPTS_AGGREGATE, period, reportingCurrency,
                        details, jwt).thenReturn(bytes))
                .map(bytes -> attach(bytes, filename));
    }

    // ── Per-member (paginated) ─────────────────────────────────────────────

    @GetMapping("/members")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.RECEIPTS_REPORT)
    @Operation(summary = "Per-member receipts aggregate — paginated + searchable",
            description = "Individual-line insurance (LIFE / TRAVEL / DISABILITY / VEHICLE / PROPERTY / "
                        + "individual HEALTH) plus direct top-up payments from grouped-line members.")
    public Mono<ReportResponse<PageResponse<ReceiptsSummaryRow>>> membersReport(
            @RequestParam String periodStart,
            @RequestParam String periodEnd,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String insuranceLine,
            @RequestParam(required = false) UUID schemeId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String reportingCurrency) {
        ReportPeriod period = ReportPeriod.parseFromQueryParams(periodStart, periodEnd, null);
        return envelopeBuilder.build(
                ReportKey.RECEIPTS_REPORT,
                period,
                reportingCurrency,
                receiptsReportService.perMemberSummary(period.periodStart(), period.periodEnd(),
                        search, insuranceLine, schemeId, page, size),
                receiptsReportService.perMemberPerCurrencyTotals(period.periodStart(), period.periodEnd(),
                        search, insuranceLine, schemeId));
    }

    @GetMapping("/members/export/excel")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.RECEIPTS_REPORT)
    @Operation(summary = "Download the per-member receipts report as XLSX",
            description = "Capped at 10 000 rows; refine `search` / `insuranceLine` / `schemeId` if exceeded.")
    public Mono<ResponseEntity<byte[]>> exportMembersExcel(
            @RequestParam String periodStart,
            @RequestParam String periodEnd,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String insuranceLine,
            @RequestParam(required = false) UUID schemeId,
            @RequestParam(required = false) String reportingCurrency,
            @AuthenticationPrincipal Jwt jwt) {
        ReportPeriod period = ReportPeriod.parseFromQueryParams(periodStart, periodEnd, null);
        String filename = "receipts-members-" + period.periodStart() + "-to-" + period.periodEnd() + ".xlsx";
        Map<String, Object> details = new LinkedHashMap<>();
        if (search != null) details.put("search", search);
        if (insuranceLine != null) details.put("insuranceLine", insuranceLine);
        if (schemeId != null) details.put("schemeId", schemeId.toString());
        return receiptsExcelService.membersReportExcel(period.periodStart(), period.periodEnd(),
                        search, insuranceLine, schemeId, reportingCurrency)
                .flatMap(bytes -> publishExportEvent(ReportKey.RECEIPTS_REPORT, period, reportingCurrency,
                        details, jwt).thenReturn(bytes))
                .map(bytes -> attach(bytes, filename));
    }

    @GetMapping("/members/{memberId}")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.RECEIPTS_AGGREGATE)
    @Operation(summary = "Single-member receipts drill-down")
    public Mono<ReportResponse<ReceiptsDetailResponse>> memberDetail(
            @PathVariable UUID memberId,
            @RequestParam String periodStart,
            @RequestParam String periodEnd,
            @RequestParam(required = false) String transactionType,
            @RequestParam(required = false) String currency,
            @RequestParam(required = false) String reportingCurrency,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        ReportPeriod period = ReportPeriod.parseFromQueryParams(periodStart, periodEnd, null);
        return envelopeBuilder.buildNoAggregate(
                ReportKey.RECEIPTS_AGGREGATE,
                period,
                reportingCurrency,
                receiptsReportService.detail("MEMBER", memberId, period.periodStart(), period.periodEnd(),
                        transactionType, currency, page, size));
    }

    @GetMapping("/members/{memberId}/export/excel")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.RECEIPTS_AGGREGATE)
    @Operation(summary = "Download the member receipts detail as a two-sheet XLSX")
    public Mono<ResponseEntity<byte[]>> exportMemberDetailExcel(
            @PathVariable UUID memberId,
            @RequestParam String periodStart,
            @RequestParam String periodEnd,
            @RequestParam(required = false) String transactionType,
            @RequestParam(required = false) String currency,
            @RequestParam(required = false) String reportingCurrency,
            @AuthenticationPrincipal Jwt jwt) {
        ReportPeriod period = ReportPeriod.parseFromQueryParams(periodStart, periodEnd, null);
        String filename = "receipts-member-" + memberId + "-" + period.periodStart() + "-to-" + period.periodEnd() + ".xlsx";
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("dimension", "MEMBER");
        details.put("dimensionId", memberId.toString());
        if (transactionType != null) details.put("transactionType", transactionType);
        if (currency != null) details.put("currency", currency);
        return receiptsExcelService.detailExcel("MEMBER", memberId, "Member",
                        period.periodStart(), period.periodEnd(), transactionType, currency, reportingCurrency)
                .flatMap(bytes -> publishExportEvent(ReportKey.RECEIPTS_AGGREGATE, period, reportingCurrency,
                        details, jwt).thenReturn(bytes))
                .map(bytes -> attach(bytes, filename));
    }

    // ── Internal helpers ───────────────────────────────────────────────────

    private Mono<Void> publishExportEvent(ReportKey key, ReportPeriod period,
                                           String reportingCurrency, Map<String, Object> extra, Jwt jwt) {
        Map<String, Object> details = new LinkedHashMap<>();
        if (period != null) {
            if (period.periodStart() != null) details.put("periodStart", period.periodStart().toString());
            if (period.periodEnd()   != null) details.put("periodEnd",   period.periodEnd().toString());
        }
        if (reportingCurrency != null && !reportingCurrency.isBlank()) {
            details.put("reportingCurrency", reportingCurrency);
        }
        if (extra != null) details.putAll(extra);
        return Mono.deferContextual(ctx -> securityEventPublisher.publishDataAccess(
                TenantContext.get(ctx),
                AuditActor.id(jwt),
                AuditActor.email(jwt),
                key.name(),
                details));
    }

    private static ResponseEntity<byte[]> attach(byte[] bytes, String filename) {
        return ResponseEntity.ok()
                .contentType(XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + filename + "\"")
                .body(bytes);
    }
}
