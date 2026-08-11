package com.medfund.contributions.controller;

import com.medfund.contributions.dto.BillingAggregateRow;
import com.medfund.contributions.dto.GroupBillingDetailResponse;
import com.medfund.contributions.dto.GroupBillingSummaryRow;
import com.medfund.contributions.dto.MemberBillingDetailResponse;
import com.medfund.contributions.dto.MemberBillingSummaryRow;
import com.medfund.contributions.dto.PageResponse;
import com.medfund.contributions.dto.SchemeBillingDetailResponse;
import com.medfund.contributions.dto.SchemeBillingSummaryRow;
import com.medfund.contributions.service.BillingReportExcelService;
import com.medfund.contributions.service.BillingReportService;
import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.report.PerCurrencyTotal;
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

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 2 billing-report family — per-scheme + per-group aggregates,
 * per-scheme + per-group detail with monthly breakdown, plus the
 * cross-service {@code /aggregate/billing} endpoint that Phase 3
 * (collection rate) and Phase 5 (loss ratio) consume.
 *
 * <p>All reads are wrapped in {@link ReportResponse} via
 * {@link ReportEnvelopeBuilder} — the envelope carries native-currency
 * ledger totals per {@link PerCurrencyTotal} (G17-G18) plus best-effort
 * native→reporting FX rates (G28). Reports are gated by
 * {@link RequiresReport}; exports fire a {@code DATA_ACCESS}
 * {@code SecurityEvent} before returning bytes (G24).
 */
@RestController
@RequestMapping("/api/v1/reports/billing")
@RequiredArgsConstructor
@Tag(name = "Billing reports",
        description = "Phase 2 billing-report family — per-scheme + per-group aggregates and details, "
                    + "each returning a native-currency ledger breakdown alongside optional "
                    + "reporting-currency conversion.")
@SecurityRequirement(name = "bearer-jwt")
public class BillingReportController {

    private static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final BillingReportService billingReportService;
    private final BillingReportExcelService billingReportExcelService;
    private final ReportEnvelopeBuilder envelopeBuilder;
    private final SecurityEventPublisher securityEventPublisher;

    // ── Per-scheme aggregate ────────────────────────────────────────────────

    @GetMapping("/schemes")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.BILLING_REPORT)
    @Operation(summary = "Per-scheme billing aggregate for the window",
            description = "One row per (scheme, currency). Only committed contributions "
                        + "(invoice_id IS NOT NULL) are counted. Age bands are computed at "
                        + "query time from the beneficiary's date_of_birth versus the "
                        + "contribution's period_start.")
    public Mono<ReportResponse<List<SchemeBillingSummaryRow>>> schemesReport(
            @Parameter(description = "ISO date — first day of the reporting window (inclusive)")
            @RequestParam String periodStart,
            @Parameter(description = "ISO date — last day of the reporting window (inclusive)")
            @RequestParam String periodEnd,
            @Parameter(description = "Optional ISO-4217 override; defaults to the tenant's default currency")
            @RequestParam(required = false) String reportingCurrency) {
        ReportPeriod period = ReportPeriod.parseFromQueryParams(periodStart, periodEnd, null);
        return envelopeBuilder.build(
                ReportKey.BILLING_REPORT,
                period,
                reportingCurrency,
                billingReportService.perSchemeSummary(period.periodStart(), period.periodEnd()),
                billingReportService.perSchemePerCurrencyTotals(period.periodStart(), period.periodEnd()));
    }

    @GetMapping("/schemes/export/excel")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.BILLING_REPORT)
    @Operation(summary = "Download the per-scheme billing report as XLSX",
            description = "Same filter shape as /schemes. When ?reportingCurrency= is passed the "
                        + "workbook adds a rightmost 'Amount in {reportingCurrency}' column populated "
                        + "from historical FX rates; currencies whose rate is missing skip that column "
                        + "without failing the export.")
    public Mono<ResponseEntity<byte[]>> exportSchemesExcel(
            @RequestParam String periodStart,
            @RequestParam String periodEnd,
            @RequestParam(required = false) String reportingCurrency,
            @AuthenticationPrincipal Jwt jwt) {
        ReportPeriod period = ReportPeriod.parseFromQueryParams(periodStart, periodEnd, null);
        String filename = "billing-schemes-" + period.periodStart() + "-to-" + period.periodEnd() + ".xlsx";
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("periodStart", period.periodStart().toString());
        details.put("periodEnd",   period.periodEnd().toString());
        if (reportingCurrency != null && !reportingCurrency.isBlank()) {
            details.put("reportingCurrency", reportingCurrency);
        }
        return billingReportExcelService.schemeReportExcel(period.periodStart(), period.periodEnd(), reportingCurrency)
                .flatMap(bytes -> Mono.deferContextual(ctx -> securityEventPublisher.publishDataAccess(
                                TenantContext.get(ctx),
                                AuditActor.id(jwt),
                                AuditActor.email(jwt),
                                ReportKey.BILLING_REPORT.name(),
                                details))
                        .thenReturn(bytes))
                .map(bytes -> ResponseEntity.ok()
                        .contentType(XLSX)
                        .header(HttpHeaders.CONTENT_DISPOSITION,
                                "attachment; filename=\"" + filename + "\"")
                        .body(bytes));
    }

    @GetMapping("/schemes/{schemeId}")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.SCHEME_BILLING_DETAIL)
    @Operation(summary = "Single-scheme billing detail with monthly breakdown")
    public Mono<ReportResponse<SchemeBillingDetailResponse>> schemeDetail(
            @PathVariable UUID schemeId,
            @RequestParam String periodStart,
            @RequestParam String periodEnd,
            @RequestParam(required = false) String reportingCurrency) {
        ReportPeriod period = ReportPeriod.parseFromQueryParams(periodStart, periodEnd, null);
        return envelopeBuilder.buildNoAggregate(
                ReportKey.SCHEME_BILLING_DETAIL,
                period,
                reportingCurrency,
                billingReportService.schemeDetail(schemeId, period.periodStart(), period.periodEnd()));
    }

    // ── Per-group aggregate ─────────────────────────────────────────────────

    @GetMapping("/groups")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.GROUP_BILLING_REPORT)
    @Operation(summary = "Per-group billing aggregate for the window",
            description = "One row per (group, currency). Only rows with a group_id and "
                        + "invoice_id IS NOT NULL are counted — a member paying individually "
                        + "does not create a group-billing row.")
    public Mono<ReportResponse<List<GroupBillingSummaryRow>>> groupsReport(
            @RequestParam String periodStart,
            @RequestParam String periodEnd,
            @RequestParam(required = false) String reportingCurrency) {
        ReportPeriod period = ReportPeriod.parseFromQueryParams(periodStart, periodEnd, null);
        return envelopeBuilder.build(
                ReportKey.GROUP_BILLING_REPORT,
                period,
                reportingCurrency,
                billingReportService.perGroupSummary(period.periodStart(), period.periodEnd()),
                billingReportService.perGroupPerCurrencyTotals(period.periodStart(), period.periodEnd()));
    }

    @GetMapping("/groups/export/excel")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.GROUP_BILLING_REPORT)
    @Operation(summary = "Download the per-group billing report as XLSX")
    public Mono<ResponseEntity<byte[]>> exportGroupsExcel(
            @RequestParam String periodStart,
            @RequestParam String periodEnd,
            @RequestParam(required = false) String reportingCurrency,
            @AuthenticationPrincipal Jwt jwt) {
        ReportPeriod period = ReportPeriod.parseFromQueryParams(periodStart, periodEnd, null);
        String filename = "billing-groups-" + period.periodStart() + "-to-" + period.periodEnd() + ".xlsx";
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("periodStart", period.periodStart().toString());
        details.put("periodEnd",   period.periodEnd().toString());
        if (reportingCurrency != null && !reportingCurrency.isBlank()) {
            details.put("reportingCurrency", reportingCurrency);
        }
        return billingReportExcelService.groupReportExcel(period.periodStart(), period.periodEnd(), reportingCurrency)
                .flatMap(bytes -> Mono.deferContextual(ctx -> securityEventPublisher.publishDataAccess(
                                TenantContext.get(ctx),
                                AuditActor.id(jwt),
                                AuditActor.email(jwt),
                                ReportKey.GROUP_BILLING_REPORT.name(),
                                details))
                        .thenReturn(bytes))
                .map(bytes -> ResponseEntity.ok()
                        .contentType(XLSX)
                        .header(HttpHeaders.CONTENT_DISPOSITION,
                                "attachment; filename=\"" + filename + "\"")
                        .body(bytes));
    }

    @GetMapping("/groups/{groupId}")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.GROUP_BILLING_DETAIL)
    @Operation(summary = "Single-group billing detail with monthly breakdown")
    public Mono<ReportResponse<GroupBillingDetailResponse>> groupDetail(
            @PathVariable UUID groupId,
            @RequestParam String periodStart,
            @RequestParam String periodEnd,
            @RequestParam(required = false) String reportingCurrency) {
        ReportPeriod period = ReportPeriod.parseFromQueryParams(periodStart, periodEnd, null);
        return envelopeBuilder.buildNoAggregate(
                ReportKey.GROUP_BILLING_DETAIL,
                period,
                reportingCurrency,
                billingReportService.groupDetail(groupId, period.periodStart(), period.periodEnd()));
    }

    // ── Per-member — Phase 3 §8 owed-back to Phase 2 ────────────────────────
    // Symmetry-fix for individual-line policies (LIFE / TRAVEL / DISABILITY /
    // VEHICLE / PROPERTY / individual HEALTH) that were missing a billing surface
    // in Phase 2. Reuses the broad BILLING_REPORT key per G21.

    @GetMapping("/members")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.BILLING_REPORT)
    @Operation(summary = "Per-member billing aggregate — paginated + searchable",
            description = "Covers individual-line policies (LIFE / TRAVEL / DISABILITY / VEHICLE / "
                        + "PROPERTY / individual HEALTH). Only contributions where member_id is set "
                        + "AND invoice_id IS NOT NULL are counted.")
    public Mono<ReportResponse<PageResponse<MemberBillingSummaryRow>>> membersReport(
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
                ReportKey.BILLING_REPORT,
                period,
                reportingCurrency,
                billingReportService.perMemberSummary(period.periodStart(), period.periodEnd(),
                        search, insuranceLine, schemeId, page, size),
                billingReportService.perMemberPerCurrencyTotals(period.periodStart(), period.periodEnd(),
                        search, insuranceLine, schemeId));
    }

    @GetMapping("/members/export/excel")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.BILLING_REPORT)
    @Operation(summary = "Download the per-member billing report as XLSX",
            description = "Capped at 10 000 rows; refine filters if exceeded.")
    public Mono<ResponseEntity<byte[]>> exportMembersExcel(
            @RequestParam String periodStart,
            @RequestParam String periodEnd,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String insuranceLine,
            @RequestParam(required = false) UUID schemeId,
            @RequestParam(required = false) String reportingCurrency,
            @AuthenticationPrincipal Jwt jwt) {
        ReportPeriod period = ReportPeriod.parseFromQueryParams(periodStart, periodEnd, null);
        String filename = "billing-members-" + period.periodStart() + "-to-" + period.periodEnd() + ".xlsx";
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("periodStart", period.periodStart().toString());
        details.put("periodEnd",   period.periodEnd().toString());
        if (search != null) details.put("search", search);
        if (insuranceLine != null) details.put("insuranceLine", insuranceLine);
        if (schemeId != null) details.put("schemeId", schemeId.toString());
        if (reportingCurrency != null && !reportingCurrency.isBlank()) {
            details.put("reportingCurrency", reportingCurrency);
        }
        return billingReportExcelService.memberReportExcel(period.periodStart(), period.periodEnd(),
                        search, insuranceLine, schemeId, reportingCurrency)
                .flatMap(bytes -> Mono.deferContextual(ctx -> securityEventPublisher.publishDataAccess(
                                TenantContext.get(ctx),
                                AuditActor.id(jwt),
                                AuditActor.email(jwt),
                                ReportKey.BILLING_REPORT.name(),
                                details))
                        .thenReturn(bytes))
                .map(bytes -> ResponseEntity.ok()
                        .contentType(XLSX)
                        .header(HttpHeaders.CONTENT_DISPOSITION,
                                "attachment; filename=\"" + filename + "\"")
                        .body(bytes));
    }

    @GetMapping("/members/{memberId}")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.BILLING_REPORT)
    @Operation(summary = "Single-member billing detail with monthly breakdown")
    public Mono<ReportResponse<MemberBillingDetailResponse>> memberDetail(
            @PathVariable UUID memberId,
            @RequestParam String periodStart,
            @RequestParam String periodEnd,
            @RequestParam(required = false) String reportingCurrency) {
        ReportPeriod period = ReportPeriod.parseFromQueryParams(periodStart, periodEnd, null);
        return envelopeBuilder.buildNoAggregate(
                ReportKey.BILLING_REPORT,
                period,
                reportingCurrency,
                billingReportService.memberDetail(memberId, period.periodStart(), period.periodEnd()));
    }
}
