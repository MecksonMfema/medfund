package com.medfund.finance.controller;

import com.medfund.finance.dto.LossRatioReportResponse;
import com.medfund.finance.dto.MemberPaymentsReportResponse;
import com.medfund.finance.service.CrossServiceReportService;
import com.medfund.finance.service.LossRatioExcelService;
import com.medfund.finance.service.MemberPaymentsExcelService;
import com.medfund.shared.audit.AuditActor;
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
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
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

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 5 cross-service reports — loss-ratio (billing vs claims) and
 * member-payments unified. Both compose billing + receipts + claims
 * aggregates from contributions-service and claims-service. Cross-service
 * peer failure is captured on the envelope's warnings list (G37 /
 * invariant #7); the report itself always succeeds with partial data.
 *
 * <p>Envelope is hand-built here (rather than via
 * {@link com.medfund.shared.report.ReportEnvelopeBuilder}) because the
 * warnings come from the cross-service fanout, not from the FX-rate
 * lookups — the builder's best-effort FX pass would overwrite them.
 * Rows stay native per-currency; no conversion (G34).
 */
@RestController
@RequestMapping("/api/v1/reports")
@RequiredArgsConstructor
@Tag(name = "Cross-service reports",
        description = "Loss-ratio (billing vs claims) and member-payments unified — composes "
                    + "billing + receipts + claims aggregates from contributions-service and "
                    + "claims-service. Peer downtime populates envelope warnings; the report "
                    + "still renders with partial data (G37).")
@SecurityRequirement(name = "bearer-jwt")
public class CrossServiceReportController {

    private static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final CrossServiceReportService crossServiceReportService;
    private final LossRatioExcelService lossRatioExcelService;
    private final MemberPaymentsExcelService memberPaymentsExcelService;
    private final ReportingCurrencyResolver currencyResolver;
    private final SecurityEventPublisher securityEventPublisher;

    @GetMapping("/billing-vs-claims")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.LOSS_RATIO)
    @Operation(summary = "Loss ratio — billed vs claimed/approved/paid per scheme and currency",
            description = "Composes the SCHEME-level billing aggregate from contributions-service with "
                        + "the claims funnel aggregate from claims-service. Peer downtime populates "
                        + "envelope warnings; the report still renders with partial data.")
    public Mono<ReportResponse<LossRatioReportResponse>> lossRatio(
            @RequestParam String periodStart,
            @RequestParam String periodEnd,
            @RequestParam(required = false) String reportingCurrency) {
        ReportPeriod period = ReportPeriod.parseFromQueryParams(periodStart, periodEnd, null);
        return Mono.deferContextual(ctx -> {
            UUID tenantId = parseTenantId(TenantContext.get(ctx));
            List<String> warnings = new ArrayList<>();
            return Mono.zip(
                    currencyResolver.resolve(tenantId, reportingCurrency),
                    crossServiceReportService.lossRatio(period.periodStart(), period.periodEnd(), warnings))
                    .map(t -> new ReportResponse<>(
                            ReportKey.LOSS_RATIO.name(),
                            period,
                            t.getT1(),
                            t.getT2(),
                            Map.of(),
                            Map.of(),
                            List.copyOf(warnings),
                            OffsetDateTime.now()));
        });
    }

    @GetMapping("/billing-vs-claims/export/excel")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.LOSS_RATIO)
    @Operation(summary = "Download the loss-ratio report as XLSX",
            description = "Single sheet: one row per (scheme, currency) with the full claims funnel, "
                        + "paid ratio %, and billed-minus-paid. Warnings appear as a meta strip when "
                        + "populated.")
    public Mono<ResponseEntity<byte[]>> lossRatioExcel(
            @RequestParam String periodStart,
            @RequestParam String periodEnd,
            @RequestParam(required = false) String reportingCurrency,
            @AuthenticationPrincipal Jwt jwt) {
        ReportPeriod period = ReportPeriod.parseFromQueryParams(periodStart, periodEnd, null);
        String filename = "loss-ratio-" + period.periodStart() + "-to-" + period.periodEnd() + ".xlsx";
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("periodStart", period.periodStart().toString());
        details.put("periodEnd",   period.periodEnd().toString());
        if (reportingCurrency != null && !reportingCurrency.isBlank()) {
            details.put("reportingCurrency", reportingCurrency);
        }
        List<String> warnings = new ArrayList<>();
        return lossRatioExcelService.workbook(period.periodStart(), period.periodEnd(), warnings)
                .flatMap(bytes -> Mono.deferContextual(ctx -> securityEventPublisher.publishDataAccess(
                                TenantContext.get(ctx),
                                AuditActor.id(jwt),
                                AuditActor.email(jwt),
                                ReportKey.LOSS_RATIO.name(),
                                details))
                        .thenReturn(bytes))
                .map(bytes -> ResponseEntity.ok()
                        .contentType(XLSX)
                        .header(HttpHeaders.CONTENT_DISPOSITION,
                                "attachment; filename=\"" + filename + "\"")
                        .body(bytes));
    }

    @GetMapping("/member-payments")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.MEMBER_PAYMENTS_UNIFIED)
    @Operation(summary = "Member payments — unified billed / received / claims-paid per member and currency",
            description = "Composes the MEMBER-level monthly billing + receipts aggregates from "
                        + "contributions-service with the MEMBER-level monthly claims-paid aggregate from "
                        + "claims-service, summed across the reporting window. Peer downtime populates "
                        + "envelope warnings; the report still renders with partial data.")
    public Mono<ReportResponse<MemberPaymentsReportResponse>> memberPayments(
            @RequestParam String periodStart,
            @RequestParam String periodEnd,
            @RequestParam(required = false) String reportingCurrency) {
        ReportPeriod period = ReportPeriod.parseFromQueryParams(periodStart, periodEnd, null);
        return Mono.deferContextual(ctx -> {
            UUID tenantId = parseTenantId(TenantContext.get(ctx));
            List<String> warnings = new ArrayList<>();
            return Mono.zip(
                    currencyResolver.resolve(tenantId, reportingCurrency),
                    crossServiceReportService.memberPayments(period.periodStart(), period.periodEnd(), warnings))
                    .map(t -> new ReportResponse<>(
                            ReportKey.MEMBER_PAYMENTS_UNIFIED.name(),
                            period,
                            t.getT1(),
                            t.getT2(),
                            Map.of(),
                            Map.of(),
                            List.copyOf(warnings),
                            OffsetDateTime.now()));
        });
    }

    @GetMapping("/member-payments/export/excel")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.MEMBER_PAYMENTS_UNIFIED)
    @Operation(summary = "Download the member-payments report as XLSX",
            description = "Single sheet: one row per (member, currency) with billed / received / "
                        + "claims-paid and the derived net position. Warnings appear as a meta strip "
                        + "when populated.")
    public Mono<ResponseEntity<byte[]>> memberPaymentsExcel(
            @RequestParam String periodStart,
            @RequestParam String periodEnd,
            @RequestParam(required = false) String reportingCurrency,
            @AuthenticationPrincipal Jwt jwt) {
        ReportPeriod period = ReportPeriod.parseFromQueryParams(periodStart, periodEnd, null);
        String filename = "member-payments-" + period.periodStart() + "-to-" + period.periodEnd() + ".xlsx";
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("periodStart", period.periodStart().toString());
        details.put("periodEnd",   period.periodEnd().toString());
        if (reportingCurrency != null && !reportingCurrency.isBlank()) {
            details.put("reportingCurrency", reportingCurrency);
        }
        List<String> warnings = new ArrayList<>();
        return memberPaymentsExcelService.workbook(period.periodStart(), period.periodEnd(), warnings)
                .flatMap(bytes -> Mono.deferContextual(ctx -> securityEventPublisher.publishDataAccess(
                                TenantContext.get(ctx),
                                AuditActor.id(jwt),
                                AuditActor.email(jwt),
                                ReportKey.MEMBER_PAYMENTS_UNIFIED.name(),
                                details))
                        .thenReturn(bytes))
                .map(bytes -> ResponseEntity.ok()
                        .contentType(XLSX)
                        .header(HttpHeaders.CONTENT_DISPOSITION,
                                "attachment; filename=\"" + filename + "\"")
                        .body(bytes));
    }

    private static UUID parseTenantId(String tenantIdStr) {
        if (tenantIdStr == null || tenantIdStr.isBlank()) return null;
        try { return UUID.fromString(tenantIdStr); } catch (IllegalArgumentException e) { return null; }
    }
}
