package com.medfund.claims.controller;

import com.medfund.claims.dto.ClaimStatusMatrixResponse;
import com.medfund.claims.dto.ClaimsDetailResponse;
import com.medfund.claims.dto.ClaimsSummaryRow;
import com.medfund.claims.dto.DenialAnalysisResponse;
import com.medfund.claims.dto.FrequencySeverityRow;
import com.medfund.claims.dto.HighCostClaimantRow;
import com.medfund.claims.dto.PageResponse;
import com.medfund.claims.dto.PreAuthActivityResponse;
import com.medfund.claims.service.ClaimsExcelService;
import com.medfund.claims.service.ClaimsReportService;
import com.medfund.claims.service.HighCostClaimantService;
import com.medfund.claims.service.PreAuthActivityService;
import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.report.FxRateReader;
import com.medfund.shared.report.PerCurrencyTotal;
import com.medfund.shared.report.ReportEnvelopeBuilder;
import com.medfund.shared.report.ReportKey;
import com.medfund.shared.report.ReportPeriod;
import com.medfund.shared.report.ReportResponse;
import com.medfund.shared.report.ReportingCurrencyResolver;
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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 4 §A claims-financial report family — per-scheme / per-provider
 * summaries (CLAIMS_SUMMARY), HIGH_COST_CLAIMANT, and PRE_AUTH_ACTIVITY,
 * plus drill-down detail for each summary dimension. Every read is wrapped
 * in {@link ReportResponse}; every export fires a {@code DATA_ACCESS}
 * {@code SecurityEvent} before returning bytes.
 *
 * <p>Period clock per G41: {@code adjudicated_at} for the financial-exposure
 * views (CLAIMS_SUMMARY / HIGH_COST_CLAIMANT), {@code requested_date} for
 * PRE_AUTH_ACTIVITY. Every row renders the claimed / approved / paid funnel
 * (G42); amounts stay native-currency (G25) with envelope FX best-effort.
 */
@RestController
@RequestMapping("/api/v1/reports/claims")
@RequiredArgsConstructor
@Tag(name = "Claims reports",
        description = "Phase 4 claims-financial family — per-scheme / per-provider summaries plus "
                    + "HIGH_COST_CLAIMANT and PRE_AUTH_ACTIVITY. Amounts are native-currency with "
                    + "envelope best-effort FX; every row carries the claimed/approved/paid funnel.")
@SecurityRequirement(name = "bearer-jwt")
public class ClaimsReportController {

    private static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final ClaimsReportService claimsReportService;
    private final HighCostClaimantService highCostClaimantService;
    private final PreAuthActivityService preAuthActivityService;
    private final ClaimsExcelService claimsExcelService;
    private final ReportEnvelopeBuilder envelopeBuilder;
    private final ReportingCurrencyResolver currencyResolver;
    private final FxRateReader fxRateReader;
    private final SecurityEventPublisher securityEventPublisher;

    // ── Per-scheme (CLAIMS_SUMMARY) ────────────────────────────────────────

    @GetMapping("/schemes")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.CLAIMS_SUMMARY)
    @Operation(summary = "Per-scheme claims aggregate for the window",
            description = "One row per (scheme, currency); claimed/approved/paid funnel (G42). "
                        + "Period clock is adjudicated_at (G41).")
    public Mono<ReportResponse<List<ClaimsSummaryRow>>> schemesReport(
            @Parameter(description = "ISO date — first day of the reporting window (inclusive)")
            @RequestParam String periodStart,
            @Parameter(description = "ISO date — last day of the reporting window (inclusive)")
            @RequestParam String periodEnd,
            @Parameter(description = "Optional ISO-4217 override; defaults to the tenant's default currency")
            @RequestParam(required = false) String reportingCurrency,
            @Parameter(description = "Optional insurance-line filter (HEALTH, LIFE, ...) applied directly "
                                   + "on claims.insurance_line")
            @RequestParam(required = false) String insuranceLine) {
        ReportPeriod period = ReportPeriod.parseFromQueryParams(periodStart, periodEnd, null);
        return envelopeBuilder.build(
                ReportKey.CLAIMS_SUMMARY,
                period,
                reportingCurrency,
                claimsReportService.perSchemeSummary(period.periodStart(), period.periodEnd(), insuranceLine),
                claimsReportService.claimsPerCurrencyTotals(period.periodStart(), period.periodEnd(), insuranceLine));
    }

    @GetMapping("/schemes/export/excel")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.CLAIMS_SUMMARY)
    @Operation(summary = "Download the per-scheme claims report as XLSX")
    public Mono<ResponseEntity<byte[]>> exportSchemesExcel(
            @RequestParam String periodStart,
            @RequestParam String periodEnd,
            @RequestParam(required = false) String reportingCurrency,
            @RequestParam(required = false) String insuranceLine,
            @AuthenticationPrincipal Jwt jwt) {
        ReportPeriod period = ReportPeriod.parseFromQueryParams(periodStart, periodEnd, null);
        String filename = "claims-schemes-" + period.periodStart() + "-to-" + period.periodEnd() + ".xlsx";
        Map<String, Object> details = new LinkedHashMap<>();
        if (insuranceLine != null && !insuranceLine.isBlank()) details.put("insuranceLine", insuranceLine);
        return claimsExcelService.schemesReportExcel(period.periodStart(), period.periodEnd(),
                        reportingCurrency, insuranceLine)
                .flatMap(bytes -> publishExportEvent(ReportKey.CLAIMS_SUMMARY, period, reportingCurrency,
                        details, jwt).thenReturn(bytes))
                .map(bytes -> attach(bytes, filename));
    }

    @GetMapping("/schemes/{schemeId}")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.CLAIMS_SUMMARY)
    @Operation(summary = "Single-scheme claims drill-down with monthly buckets + ledger",
            description = "Monthly-strip + paginated claim ledger, mirroring Phase 3 receipts-detail "
                        + "shape (G40). Optional status / provider / currency ledger filters.")
    public Mono<ReportResponse<ClaimsDetailResponse>> schemeDetail(
            @PathVariable UUID schemeId,
            @RequestParam String periodStart,
            @RequestParam String periodEnd,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID providerId,
            @RequestParam(required = false) String currency,
            @RequestParam(required = false) String reportingCurrency,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        ReportPeriod period = ReportPeriod.parseFromQueryParams(periodStart, periodEnd, null);
        return envelopeBuilder.buildNoAggregate(
                ReportKey.CLAIMS_SUMMARY,
                period,
                reportingCurrency,
                claimsReportService.detail("SCHEME", schemeId, period.periodStart(), period.periodEnd(),
                        status, providerId, currency, page, size));
    }

    @GetMapping("/schemes/{schemeId}/export/excel")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.CLAIMS_SUMMARY)
    @Operation(summary = "Download the scheme claims detail as a two-sheet XLSX",
            description = "Sheet 1 monthly buckets, sheet 2 claim ledger (10k-row cap).")
    public Mono<ResponseEntity<byte[]>> exportSchemeDetailExcel(
            @PathVariable UUID schemeId,
            @RequestParam String periodStart,
            @RequestParam String periodEnd,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID providerId,
            @RequestParam(required = false) String currency,
            @RequestParam(required = false) String reportingCurrency,
            @AuthenticationPrincipal Jwt jwt) {
        ReportPeriod period = ReportPeriod.parseFromQueryParams(periodStart, periodEnd, null);
        String filename = "claims-scheme-" + schemeId + "-" + period.periodStart() + "-to-" + period.periodEnd() + ".xlsx";
        return exportDetailExcel("SCHEME", schemeId, "Scheme",
                period, status, providerId, currency, reportingCurrency, jwt, filename);
    }

    // ── Per-provider (CLAIMS_SUMMARY) ──────────────────────────────────────

    @GetMapping("/providers")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.CLAIMS_SUMMARY)
    @Operation(summary = "Per-provider claims aggregate for the window",
            description = "One row per (provider, currency); claimed/approved/paid funnel (G42). "
                        + "Period clock is adjudicated_at (G41).")
    public Mono<ReportResponse<List<ClaimsSummaryRow>>> providersReport(
            @RequestParam String periodStart,
            @RequestParam String periodEnd,
            @RequestParam(required = false) String reportingCurrency,
            @RequestParam(required = false) String insuranceLine) {
        ReportPeriod period = ReportPeriod.parseFromQueryParams(periodStart, periodEnd, null);
        return envelopeBuilder.build(
                ReportKey.CLAIMS_SUMMARY,
                period,
                reportingCurrency,
                claimsReportService.perProviderSummary(period.periodStart(), period.periodEnd(), insuranceLine),
                claimsReportService.claimsPerCurrencyTotals(period.periodStart(), period.periodEnd(), insuranceLine));
    }

    @GetMapping("/providers/export/excel")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.CLAIMS_SUMMARY)
    @Operation(summary = "Download the per-provider claims report as XLSX")
    public Mono<ResponseEntity<byte[]>> exportProvidersExcel(
            @RequestParam String periodStart,
            @RequestParam String periodEnd,
            @RequestParam(required = false) String reportingCurrency,
            @RequestParam(required = false) String insuranceLine,
            @AuthenticationPrincipal Jwt jwt) {
        ReportPeriod period = ReportPeriod.parseFromQueryParams(periodStart, periodEnd, null);
        String filename = "claims-providers-" + period.periodStart() + "-to-" + period.periodEnd() + ".xlsx";
        Map<String, Object> details = new LinkedHashMap<>();
        if (insuranceLine != null && !insuranceLine.isBlank()) details.put("insuranceLine", insuranceLine);
        return claimsExcelService.providersReportExcel(period.periodStart(), period.periodEnd(),
                        reportingCurrency, insuranceLine)
                .flatMap(bytes -> publishExportEvent(ReportKey.CLAIMS_SUMMARY, period, reportingCurrency,
                        details, jwt).thenReturn(bytes))
                .map(bytes -> attach(bytes, filename));
    }

    @GetMapping("/providers/{providerId}")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.CLAIMS_SUMMARY)
    @Operation(summary = "Single-provider claims drill-down with monthly buckets + ledger",
            description = "Same detail shape as the scheme drill-down.")
    public Mono<ReportResponse<ClaimsDetailResponse>> providerDetail(
            @PathVariable UUID providerId,
            @RequestParam String periodStart,
            @RequestParam String periodEnd,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String currency,
            @RequestParam(required = false) String reportingCurrency,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        ReportPeriod period = ReportPeriod.parseFromQueryParams(periodStart, periodEnd, null);
        return envelopeBuilder.buildNoAggregate(
                ReportKey.CLAIMS_SUMMARY,
                period,
                reportingCurrency,
                claimsReportService.detail("PROVIDER", providerId, period.periodStart(), period.periodEnd(),
                        status, null, currency, page, size));
    }

    @GetMapping("/providers/{providerId}/export/excel")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.CLAIMS_SUMMARY)
    @Operation(summary = "Download the provider claims detail as a two-sheet XLSX",
            description = "Sheet 1 monthly buckets, sheet 2 claim ledger (10k-row cap).")
    public Mono<ResponseEntity<byte[]>> exportProviderDetailExcel(
            @PathVariable UUID providerId,
            @RequestParam String periodStart,
            @RequestParam String periodEnd,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String currency,
            @RequestParam(required = false) String reportingCurrency,
            @AuthenticationPrincipal Jwt jwt) {
        ReportPeriod period = ReportPeriod.parseFromQueryParams(periodStart, periodEnd, null);
        String filename = "claims-provider-" + providerId + "-" + period.periodStart() + "-to-" + period.periodEnd() + ".xlsx";
        return exportDetailExcel("PROVIDER", providerId, "Provider",
                period, status, null, currency, reportingCurrency, jwt, filename);
    }

    // ── Per-group (CLAIMS_SUMMARY, §B G45) ────────────────────────────────

    @GetMapping("/groups")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.CLAIMS_SUMMARY)
    @Operation(summary = "Per-group claims aggregate for the window",
            description = "One row per (group, currency); groups resolved through members.group_id. "
                        + "Ungrouped members join an 'Ungrouped' pseudo-row. Period clock is adjudicated_at.")
    public Mono<ReportResponse<List<ClaimsSummaryRow>>> groupsReport(
            @RequestParam String periodStart,
            @RequestParam String periodEnd,
            @RequestParam(required = false) String reportingCurrency,
            @RequestParam(required = false) String insuranceLine) {
        ReportPeriod period = ReportPeriod.parseFromQueryParams(periodStart, periodEnd, null);
        return envelopeBuilder.build(
                ReportKey.CLAIMS_SUMMARY,
                period,
                reportingCurrency,
                claimsReportService.perGroupSummary(period.periodStart(), period.periodEnd(), insuranceLine),
                claimsReportService.claimsPerCurrencyTotals(period.periodStart(), period.periodEnd(), insuranceLine));
    }

    @GetMapping("/groups/export/excel")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.CLAIMS_SUMMARY)
    @Operation(summary = "Download the per-group claims report as XLSX")
    public Mono<ResponseEntity<byte[]>> exportGroupsExcel(
            @RequestParam String periodStart,
            @RequestParam String periodEnd,
            @RequestParam(required = false) String reportingCurrency,
            @RequestParam(required = false) String insuranceLine,
            @AuthenticationPrincipal Jwt jwt) {
        ReportPeriod period = ReportPeriod.parseFromQueryParams(periodStart, periodEnd, null);
        String filename = "claims-groups-" + period.periodStart() + "-to-" + period.periodEnd() + ".xlsx";
        Map<String, Object> details = new LinkedHashMap<>();
        if (insuranceLine != null && !insuranceLine.isBlank()) details.put("insuranceLine", insuranceLine);
        return claimsExcelService.groupsReportExcel(period.periodStart(), period.periodEnd(),
                        reportingCurrency, insuranceLine)
                .flatMap(bytes -> publishExportEvent(ReportKey.CLAIMS_SUMMARY, period, reportingCurrency,
                        details, jwt).thenReturn(bytes))
                .map(bytes -> attach(bytes, filename));
    }

    @GetMapping("/groups/{groupId}")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.CLAIMS_SUMMARY)
    @Operation(summary = "Single-group claims drill-down with monthly buckets + ledger",
            description = "Same detail shape as the scheme drill-down (G40).")
    public Mono<ReportResponse<ClaimsDetailResponse>> groupDetail(
            @PathVariable UUID groupId,
            @RequestParam String periodStart,
            @RequestParam String periodEnd,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID providerId,
            @RequestParam(required = false) String currency,
            @RequestParam(required = false) String reportingCurrency,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        ReportPeriod period = ReportPeriod.parseFromQueryParams(periodStart, periodEnd, null);
        return envelopeBuilder.buildNoAggregate(
                ReportKey.CLAIMS_SUMMARY,
                period,
                reportingCurrency,
                claimsReportService.detail("GROUP", groupId, period.periodStart(), period.periodEnd(),
                        status, providerId, currency, page, size));
    }

    @GetMapping("/groups/{groupId}/export/excel")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.CLAIMS_SUMMARY)
    @Operation(summary = "Download the group claims detail as a two-sheet XLSX")
    public Mono<ResponseEntity<byte[]>> exportGroupDetailExcel(
            @PathVariable UUID groupId,
            @RequestParam String periodStart,
            @RequestParam String periodEnd,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID providerId,
            @RequestParam(required = false) String currency,
            @RequestParam(required = false) String reportingCurrency,
            @AuthenticationPrincipal Jwt jwt) {
        ReportPeriod period = ReportPeriod.parseFromQueryParams(periodStart, periodEnd, null);
        String filename = "claims-group-" + groupId + "-" + period.periodStart() + "-to-" + period.periodEnd() + ".xlsx";
        return exportDetailExcel("GROUP", groupId, "Group",
                period, status, providerId, currency, reportingCurrency, jwt, filename);
    }

    // ── Per-member (CLAIMS_SUMMARY, §B G45) ───────────────────────────────

    @GetMapping("/members")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.CLAIMS_SUMMARY)
    @Operation(summary = "Per-member claims aggregate — paginated + searchable",
            description = "One row per (member, insurance line, currency). Search is plain ILIKE over "
                        + "member_number / first / last name (pg_trgm absent). Optional scheme / provider "
                        + "filters; the envelope's perCurrency totals carry the same filtered set (G18). "
                        + "Period clock is adjudicated_at.")
    public Mono<ReportResponse<PageResponse<ClaimsSummaryRow>>> membersReport(
            @RequestParam String periodStart,
            @RequestParam String periodEnd,
            @RequestParam(required = false) String reportingCurrency,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String insuranceLine,
            @RequestParam(required = false) UUID schemeId,
            @RequestParam(required = false) UUID providerId) {
        ReportPeriod period = ReportPeriod.parseFromQueryParams(periodStart, periodEnd, null);
        return envelopeBuilder.build(
                ReportKey.CLAIMS_SUMMARY,
                period,
                reportingCurrency,
                claimsReportService.perMemberSummary(period.periodStart(), period.periodEnd(),
                        search, insuranceLine, schemeId, providerId, page, size),
                claimsReportService.memberPerCurrencyTotals(period.periodStart(), period.periodEnd(),
                        search, insuranceLine, schemeId, providerId));
    }

    @GetMapping("/members/export/excel")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.CLAIMS_SUMMARY)
    @Operation(summary = "Download the per-member claims report as XLSX (10k-row cap)")
    public Mono<ResponseEntity<byte[]>> exportMembersExcel(
            @RequestParam String periodStart,
            @RequestParam String periodEnd,
            @RequestParam(required = false) String reportingCurrency,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String insuranceLine,
            @RequestParam(required = false) UUID schemeId,
            @RequestParam(required = false) UUID providerId,
            @AuthenticationPrincipal Jwt jwt) {
        ReportPeriod period = ReportPeriod.parseFromQueryParams(periodStart, periodEnd, null);
        String filename = "claims-members-" + period.periodStart() + "-to-" + period.periodEnd() + ".xlsx";
        Map<String, Object> details = new LinkedHashMap<>();
        if (search != null && !search.isBlank()) details.put("search", search);
        if (insuranceLine != null && !insuranceLine.isBlank()) details.put("insuranceLine", insuranceLine);
        if (schemeId != null) details.put("schemeId", schemeId.toString());
        if (providerId != null) details.put("providerId", providerId.toString());
        return claimsExcelService.membersReportExcel(period.periodStart(), period.periodEnd(),
                        search, insuranceLine, schemeId, providerId, reportingCurrency)
                .flatMap(bytes -> publishExportEvent(ReportKey.CLAIMS_SUMMARY, period, reportingCurrency,
                        details, jwt).thenReturn(bytes))
                .map(bytes -> attach(bytes, filename));
    }

    @GetMapping("/members/{memberId}")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.CLAIMS_SUMMARY)
    @Operation(summary = "Single-member claims drill-down with monthly buckets + ledger",
            description = "Same detail shape as the scheme drill-down (G40).")
    public Mono<ReportResponse<ClaimsDetailResponse>> memberDetail(
            @PathVariable UUID memberId,
            @RequestParam String periodStart,
            @RequestParam String periodEnd,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID providerId,
            @RequestParam(required = false) String currency,
            @RequestParam(required = false) String reportingCurrency,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        ReportPeriod period = ReportPeriod.parseFromQueryParams(periodStart, periodEnd, null);
        return envelopeBuilder.buildNoAggregate(
                ReportKey.CLAIMS_SUMMARY,
                period,
                reportingCurrency,
                claimsReportService.detail("MEMBER", memberId, period.periodStart(), period.periodEnd(),
                        status, providerId, currency, page, size));
    }

    @GetMapping("/members/{memberId}/export/excel")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.CLAIMS_SUMMARY)
    @Operation(summary = "Download the member claims detail as a two-sheet XLSX")
    public Mono<ResponseEntity<byte[]>> exportMemberDetailExcel(
            @PathVariable UUID memberId,
            @RequestParam String periodStart,
            @RequestParam String periodEnd,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID providerId,
            @RequestParam(required = false) String currency,
            @RequestParam(required = false) String reportingCurrency,
            @AuthenticationPrincipal Jwt jwt) {
        ReportPeriod period = ReportPeriod.parseFromQueryParams(periodStart, periodEnd, null);
        String filename = "claims-member-" + memberId + "-" + period.periodStart() + "-to-" + period.periodEnd() + ".xlsx";
        return exportDetailExcel("MEMBER", memberId, "Member",
                period, status, providerId, currency, reportingCurrency, jwt, filename);
    }

    // ── CLAIM_STATUS_LIST (G49) ────────────────────────────────────────────

    @GetMapping("/status-matrix")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.CLAIM_STATUS_LIST)
    @Operation(summary = "Claim pipeline aging matrix over the submission window",
            description = "One cell per (status, age bucket, currency); ages computed relative to "
                        + "NOW() at report time (G49). Buckets are 0-3 / 4-7 / 8-14 / 15-30 / >30 days "
                        + "since submission. Statuses normalise to upper case.")
    public Mono<ReportResponse<ClaimStatusMatrixResponse>> statusMatrix(
            @RequestParam String submittedFrom,
            @RequestParam String submittedTo,
            @RequestParam(required = false) String reportingCurrency,
            @RequestParam(required = false) String insuranceLine) {
        ReportPeriod period = ReportPeriod.parseFromQueryParams(submittedFrom, submittedTo, null);
        return envelopeBuilder.buildNoAggregate(
                ReportKey.CLAIM_STATUS_LIST,
                period,
                reportingCurrency,
                claimsReportService.statusMatrix(period.periodStart(), period.periodEnd(), insuranceLine));
    }

    @GetMapping("/status-matrix/drill")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.CLAIM_STATUS_LIST)
    @Operation(summary = "Paged claim ledger for one status-matrix cell",
            description = "Repeats the age-bucket CASE in the WHERE so the drill is exactly the ledger "
                        + "that built the clicked cell. Status / ageBucket optional — null renders the "
                        + "whole submission window.")
    public Mono<ReportResponse<PageResponse<ClaimsDetailResponse.ClaimLedgerRow>>> statusMatrixDrill(
            @RequestParam String submittedFrom,
            @RequestParam String submittedTo,
            @RequestParam(required = false) String reportingCurrency,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String ageBucket,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        ReportPeriod period = ReportPeriod.parseFromQueryParams(submittedFrom, submittedTo, null);
        return envelopeBuilder.buildNoAggregate(
                ReportKey.CLAIM_STATUS_LIST,
                period,
                reportingCurrency,
                claimsReportService.statusMatrixDrill(period.periodStart(), period.periodEnd(),
                        status, ageBucket, page, size));
    }

    @GetMapping("/status-matrix/export/excel")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.CLAIM_STATUS_LIST)
    @Operation(summary = "Download the status matrix as a two-sheet XLSX",
            description = "Sheet 1 the matrix counts grid, sheet 2 the full-window drill ledger (10k-row cap).")
    public Mono<ResponseEntity<byte[]>> exportStatusMatrixExcel(
            @RequestParam String submittedFrom,
            @RequestParam String submittedTo,
            @RequestParam(required = false) String reportingCurrency,
            @RequestParam(required = false) String insuranceLine,
            @AuthenticationPrincipal Jwt jwt) {
        ReportPeriod period = ReportPeriod.parseFromQueryParams(submittedFrom, submittedTo, null);
        String filename = "claim-status-matrix-" + period.periodStart() + "-to-" + period.periodEnd() + ".xlsx";
        Map<String, Object> details = new LinkedHashMap<>();
        if (insuranceLine != null && !insuranceLine.isBlank()) details.put("insuranceLine", insuranceLine);
        return claimsExcelService.statusMatrixExcel(period.periodStart(), period.periodEnd(),
                        reportingCurrency, insuranceLine)
                .flatMap(bytes -> publishExportEvent(ReportKey.CLAIM_STATUS_LIST, period, reportingCurrency,
                        details, jwt).thenReturn(bytes))
                .map(bytes -> attach(bytes, filename));
    }

    // ── DENIAL_ANALYSIS (G47) ──────────────────────────────────────────────

    @GetMapping("/denial-analysis")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.DENIAL_ANALYSIS)
    @Operation(summary = "Composite denial analysis over the REJECTED claim set",
            description = "Four views — by rejection category, by rejection code, by provider (with "
                        + "denial rate = denied/total, always FX-safe), and a monthly trend that only "
                        + "populates for multi-month windows. Primary column is claimed_amount (G42/G47); "
                        + "amounts stay native-currency.")
    public Mono<ReportResponse<DenialAnalysisResponse>> denialAnalysis(
            @RequestParam String periodStart,
            @RequestParam String periodEnd,
            @RequestParam(required = false) String reportingCurrency,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String code,
            @RequestParam(required = false) UUID providerId) {
        ReportPeriod period = ReportPeriod.parseFromQueryParams(periodStart, periodEnd, null);
        return envelopeBuilder.buildNoAggregate(
                ReportKey.DENIAL_ANALYSIS,
                period,
                reportingCurrency,
                claimsReportService.denialAnalysis(period.periodStart(), period.periodEnd(),
                        category, code, providerId));
    }

    @GetMapping("/denial-analysis/export/excel")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.DENIAL_ANALYSIS)
    @Operation(summary = "Download the denial analysis as a three-sheet XLSX",
            description = "Sheets: Categories / Codes / Providers.")
    public Mono<ResponseEntity<byte[]>> exportDenialAnalysisExcel(
            @RequestParam String periodStart,
            @RequestParam String periodEnd,
            @RequestParam(required = false) String reportingCurrency,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String code,
            @RequestParam(required = false) UUID providerId,
            @AuthenticationPrincipal Jwt jwt) {
        ReportPeriod period = ReportPeriod.parseFromQueryParams(periodStart, periodEnd, null);
        String filename = "denial-analysis-" + period.periodStart() + "-to-" + period.periodEnd() + ".xlsx";
        Map<String, Object> details = new LinkedHashMap<>();
        if (category != null && !category.isBlank()) details.put("category", category);
        if (code != null && !code.isBlank()) details.put("code", code);
        if (providerId != null) details.put("providerId", providerId.toString());
        return claimsExcelService.denialAnalysisExcel(period.periodStart(), period.periodEnd(),
                        category, code, providerId, reportingCurrency)
                .flatMap(bytes -> publishExportEvent(ReportKey.DENIAL_ANALYSIS, period, reportingCurrency,
                        details, jwt).thenReturn(bytes))
                .map(bytes -> attach(bytes, filename));
    }

    // ── CLAIMS_FREQUENCY_SEVERITY (G48) ────────────────────────────────────

    @GetMapping("/frequency-severity")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.CLAIMS_FREQUENCY_SEVERITY)
    @Operation(summary = "Frequency + severity per (scheme, line, currency)",
            description = "Service-date clock. Severity = Postgres PERCENTILE_CONT mean / median / P95; "
                        + "frequency = claims ÷ exposure-member-months annualised. Exposure uses the G48 "
                        + "fallback (active members × days ÷ 30.4375) because member_status_history is "
                        + "absent — the envelope carries the caveat in warnings.")
    public Mono<ReportResponse<List<FrequencySeverityRow>>> frequencySeverity(
            @RequestParam String serviceFrom,
            @RequestParam String serviceTo,
            @RequestParam(required = false) String reportingCurrency,
            @RequestParam(required = false) String insuranceLine) {
        ReportPeriod period = ReportPeriod.parseFromQueryParams(serviceFrom, serviceTo, null);
        return frequencySeverityEnvelope(period, reportingCurrency, insuranceLine);
    }

    @GetMapping("/frequency-severity/export/excel")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.CLAIMS_FREQUENCY_SEVERITY)
    @Operation(summary = "Download the frequency & severity matrix as XLSX",
            description = "Single sheet, native-currency severity columns.")
    public Mono<ResponseEntity<byte[]>> exportFrequencySeverityExcel(
            @RequestParam String serviceFrom,
            @RequestParam String serviceTo,
            @RequestParam(required = false) String reportingCurrency,
            @RequestParam(required = false) String insuranceLine,
            @AuthenticationPrincipal Jwt jwt) {
        ReportPeriod period = ReportPeriod.parseFromQueryParams(serviceFrom, serviceTo, null);
        String filename = "claims-frequency-severity-" + period.periodStart() + "-to-" + period.periodEnd() + ".xlsx";
        Map<String, Object> details = new LinkedHashMap<>();
        if (insuranceLine != null && !insuranceLine.isBlank()) details.put("insuranceLine", insuranceLine);
        return claimsExcelService.frequencySeverityExcel(period.periodStart(), period.periodEnd(),
                        reportingCurrency, insuranceLine)
                .flatMap(bytes -> publishExportEvent(ReportKey.CLAIMS_FREQUENCY_SEVERITY, period, reportingCurrency,
                        details, jwt).thenReturn(bytes))
                .map(bytes -> attach(bytes, filename));
    }

    // ── HIGH_COST_CLAIMANT (G46) ───────────────────────────────────────────

    @GetMapping("/high-cost-claimants")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.HIGH_COST_CLAIMANT)
    @Operation(summary = "Members whose cumulative paid claims clear the tenant threshold",
            description = "Threshold read from the tenant config table (V132); converted to the "
                        + "reporting currency at period end (fail-loud on missing FX per G28). A "
                        + "missing threshold config renders an empty report with a warning — not an "
                        + "error. Period clock is adjudicated_at.")
    public Mono<ReportResponse<List<HighCostClaimantRow>>> highCostClaimants(
            @RequestParam String periodStart,
            @RequestParam String periodEnd,
            @RequestParam(required = false) String reportingCurrency) {
        ReportPeriod period = ReportPeriod.parseFromQueryParams(periodStart, periodEnd, null);
        return highCostEnvelope(period, reportingCurrency);
    }

    @GetMapping("/high-cost-claimants/{memberId}")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.HIGH_COST_CLAIMANT)
    @Operation(summary = "One member's contributing-claims ledger",
            description = "Paginated ledger of the claims (paid_amount > 0 in the window) that fed "
                        + "the member's high-cost total.")
    public Mono<ReportResponse<PageResponse<ClaimsDetailResponse.ClaimLedgerRow>>> highCostMemberDetail(
            @PathVariable UUID memberId,
            @RequestParam String periodStart,
            @RequestParam String periodEnd,
            @RequestParam(required = false) String reportingCurrency,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        ReportPeriod period = ReportPeriod.parseFromQueryParams(periodStart, periodEnd, null);
        return envelopeBuilder.buildNoAggregate(
                ReportKey.HIGH_COST_CLAIMANT,
                period,
                reportingCurrency,
                highCostClaimantService.memberDetail(memberId, period.periodStart(), period.periodEnd(), page, size));
    }

    @GetMapping("/high-cost-claimants/export/excel")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.HIGH_COST_CLAIMANT)
    @Operation(summary = "Download the high-cost claimants report as XLSX",
            description = "Cumulative + individual funnel columns; includes the converted reporting-currency "
                        + "column when a reporting currency is passed.")
    public Mono<ResponseEntity<byte[]>> exportHighCostClaimantsExcel(
            @RequestParam String periodStart,
            @RequestParam String periodEnd,
            @RequestParam(required = false) String reportingCurrency,
            @AuthenticationPrincipal Jwt jwt) {
        ReportPeriod period = ReportPeriod.parseFromQueryParams(periodStart, periodEnd, null);
        String filename = "high-cost-claimants-" + period.periodStart() + "-to-" + period.periodEnd() + ".xlsx";
        return claimsExcelService.highCostClaimantsExcel(period.periodStart(), period.periodEnd(), reportingCurrency)
                .flatMap(bytes -> publishExportEvent(ReportKey.HIGH_COST_CLAIMANT, period, reportingCurrency,
                        Map.of(), jwt).thenReturn(bytes))
                .map(bytes -> attach(bytes, filename));
    }

    // ── PRE_AUTH_ACTIVITY (G43) ────────────────────────────────────────────

    @GetMapping("/pre-auth-activity")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.PRE_AUTH_ACTIVITY)
    @Operation(summary = "Pre-auth activity on the requested-date clock",
            description = "Per (status, currency): count, requested/approved totals, avg decision "
                        + "time. Composed with the claims-side R04/R05 rejection signal as a "
                        + "proxy-utilisation companion metric (G43 / F55).")
    public Mono<ReportResponse<PreAuthActivityResponse>> preAuthActivity(
            @RequestParam String periodStart,
            @RequestParam String periodEnd,
            @RequestParam(required = false) String reportingCurrency,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID providerId) {
        ReportPeriod period = ReportPeriod.parseFromQueryParams(periodStart, periodEnd, null);
        return envelopeBuilder.buildNoAggregate(
                ReportKey.PRE_AUTH_ACTIVITY,
                period,
                reportingCurrency,
                preAuthActivityService.activity(period.periodStart(), period.periodEnd(), status, providerId));
    }

    @GetMapping("/pre-auth-activity/export/excel")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.PRE_AUTH_ACTIVITY)
    @Operation(summary = "Download the pre-auth activity report as XLSX",
            description = "Single sheet: per-status rows + a footer row for the R04/R05 signal.")
    public Mono<ResponseEntity<byte[]>> exportPreAuthActivityExcel(
            @RequestParam String periodStart,
            @RequestParam String periodEnd,
            @RequestParam(required = false) String reportingCurrency,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID providerId,
            @AuthenticationPrincipal Jwt jwt) {
        ReportPeriod period = ReportPeriod.parseFromQueryParams(periodStart, periodEnd, null);
        String filename = "pre-auth-activity-" + period.periodStart() + "-to-" + period.periodEnd() + ".xlsx";
        Map<String, Object> details = new LinkedHashMap<>();
        if (status != null && !status.isBlank()) details.put("status", status);
        if (providerId != null) details.put("providerId", providerId.toString());
        return claimsExcelService.preAuthActivityExcel(period.periodStart(), period.periodEnd(),
                        reportingCurrency, status, providerId)
                .flatMap(bytes -> publishExportEvent(ReportKey.PRE_AUTH_ACTIVITY, period, reportingCurrency,
                        details, jwt).thenReturn(bytes))
                .map(bytes -> attach(bytes, filename));
    }

    // ── Internal helpers ───────────────────────────────────────────────────

    /**
     * CLAIMS_FREQUENCY_SEVERITY envelope is hand-built (like
     * {@link #highCostEnvelope}) because its warning — the G48 exposure-proxy
     * caveat — comes from report logic, not the envelope builder's FX pass.
     * The service-date window has no adjudicated-clock perCurrency twin, so
     * perCurrency stays empty; the report's own rows carry the native-currency
     * severities.
     */
    private Mono<ReportResponse<List<FrequencySeverityRow>>> frequencySeverityEnvelope(
            ReportPeriod period, String reportingCurrency, String insuranceLine) {
        return Mono.deferContextual(ctx -> {
            UUID tenantId = parseTenantId(TenantContext.get(ctx));
            return currencyResolver.resolve(tenantId, reportingCurrency)
                    .flatMap(rc -> claimsReportService.frequencySeverity(
                                    period.periodStart(), period.periodEnd(), insuranceLine)
                            .map(result -> ReportResponse.of(
                                    ReportKey.CLAIMS_FREQUENCY_SEVERITY, period, rc,
                                    result.rows(), Map.of(), Map.of(),
                                    result.exposureWarning() != null
                                            ? List.of(result.exposureWarning())
                                            : List.of())));
        });
    }

    /**
     * HIGH_COST_CLAIMANT envelope is hand-built (like
     * {@code CollectionRateReportController}) because its warnings come
     * from report logic — the config-gap — not from the envelope builder's
     * FX pass. Resolves the reporting currency first, feeds it to the
     * service, then composes perCurrency + best-effort FX + warnings.
     */
    private Mono<ReportResponse<List<HighCostClaimantRow>>> highCostEnvelope(
            ReportPeriod period, String reportingCurrency) {
        return Mono.deferContextual(ctx -> {
            UUID tenantId = parseTenantId(TenantContext.get(ctx));
            LocalDate periodEnd = period.periodEnd();
            return currencyResolver.resolve(tenantId, reportingCurrency)
                    .flatMap(rc -> highCostClaimantService.report(
                                    period.periodStart(), period.periodEnd(), rc, tenantId)
                            .flatMap(result -> claimsReportService.claimsPerCurrencyTotals(
                                            period.periodStart(), period.periodEnd(), null)
                                    .flatMap(perCurrency -> bestEffortFxRates(
                                                    perCurrency.keySet(), rc, periodEnd, tenantId)
                                            .map(rw -> {
                                                List<String> warnings = new ArrayList<>(rw.warnings());
                                                if (result.configWarning() != null) {
                                                    warnings.add(result.configWarning());
                                                }
                                                return ReportResponse.of(
                                                        ReportKey.HIGH_COST_CLAIMANT, period, rc,
                                                        result.rows(), perCurrency, rw.rates(), warnings);
                                            }))));
        });
    }

    private Mono<ResponseEntity<byte[]>> exportDetailExcel(String dimension, UUID id, String dimensionLabel,
                                                            ReportPeriod period, String status, UUID providerId,
                                                            String currency, String reportingCurrency,
                                                            Jwt jwt, String filename) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("dimension", dimension);
        details.put("dimensionId", id.toString());
        if (status != null && !status.isBlank()) details.put("status", status);
        if (providerId != null) details.put("providerId", providerId.toString());
        if (currency != null && !currency.isBlank()) details.put("currency", currency);
        return claimsExcelService.detailExcel(dimension, id, dimensionLabel,
                        period.periodStart(), period.periodEnd(), status, providerId, currency, reportingCurrency)
                .flatMap(bytes -> publishExportEvent(ReportKey.CLAIMS_SUMMARY, period, reportingCurrency,
                        details, jwt).thenReturn(bytes))
                .map(bytes -> attach(bytes, filename));
    }

    private Mono<RatesAndWarnings> bestEffortFxRates(java.util.Set<String> nativeCurrencies,
                                                     String reportingCurrency, LocalDate asOf, UUID tenantId) {
        if (nativeCurrencies == null || nativeCurrencies.isEmpty()) {
            return Mono.just(new RatesAndWarnings(Map.of(), List.of()));
        }
        Map<String, BigDecimal> rates = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        return Flux.fromIterable(nativeCurrencies)
                .flatMap(nativeCcy -> fxRateReader.findRate(nativeCcy, reportingCurrency, asOf, tenantId)
                        .doOnNext(rate -> rates.put(nativeCcy, rate))
                        .switchIfEmpty(Mono.fromRunnable(() -> warnings.add(
                                "FX not available for " + nativeCcy + "->"
                                        + reportingCurrency + " as of " + asOf)))
                        .then(Mono.just(nativeCcy)))
                .then(Mono.fromSupplier(() -> new RatesAndWarnings(rates, warnings)));
    }

    private record RatesAndWarnings(Map<String, BigDecimal> rates, List<String> warnings) {}

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

    private static UUID parseTenantId(String tenantIdStr) {
        if (tenantIdStr == null || tenantIdStr.isBlank()) return null;
        try { return UUID.fromString(tenantIdStr); } catch (IllegalArgumentException e) { return null; }
    }
}
