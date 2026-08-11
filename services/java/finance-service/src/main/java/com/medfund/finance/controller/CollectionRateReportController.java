package com.medfund.finance.controller;

import com.medfund.finance.dto.CollectionRateReportResponse;
import com.medfund.finance.service.CollectionRateExcelService;
import com.medfund.finance.service.CollectionRateReportService;
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
 * Phase 3 collection-rate report — composes billing + receipts monthly
 * aggregates from contributions-service into a per-dimension /
 * per-currency / per-month ratio. Cross-service peer failure captured
 * on the envelope's warnings list (G37 / invariant #7); the report
 * itself always succeeds with partial data.
 *
 * <p>Envelope is hand-built here (rather than via
 * {@link com.medfund.shared.report.ReportEnvelopeBuilder}) because the
 * warnings for this report come from the cross-service fanout, not from
 * the FX-rate lookups — the builder's best-effort FX pass would
 * overwrite them.
 */
@RestController
@RequestMapping("/api/v1/reports/collection-rate")
@RequiredArgsConstructor
@Tag(name = "Collection rate",
        description = "Per-dimension, per-currency, monthly collection-rate report — receipts vs "
                    + "billing. Never cross-currency conversion in the rate itself.")
@SecurityRequirement(name = "bearer-jwt")
public class CollectionRateReportController {

    private static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final CollectionRateReportService collectionRateReportService;
    private final CollectionRateExcelService collectionRateExcelService;
    private final ReportingCurrencyResolver currencyResolver;
    private final SecurityEventPublisher securityEventPublisher;

    @GetMapping
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.COLLECTION_RATE)
    @Operation(summary = "Collection rate — per dimension, per currency, monthly buckets",
            description = "Composes billing + receipts monthly aggregates from contributions-service. "
                        + "Peer downtime populates envelope warnings; the report still renders with "
                        + "partial data.")
    public Mono<ReportResponse<CollectionRateReportResponse>> report(
            @RequestParam String periodStart,
            @RequestParam String periodEnd,
            @RequestParam(required = false) String reportingCurrency) {
        ReportPeriod period = ReportPeriod.parseFromQueryParams(periodStart, periodEnd, null);
        return Mono.deferContextual(ctx -> {
            UUID tenantId = parseTenantId(TenantContext.get(ctx));
            List<String> warnings = new ArrayList<>();
            return Mono.zip(
                    currencyResolver.resolve(tenantId, reportingCurrency),
                    collectionRateReportService.compute(period.periodStart(), period.periodEnd(), warnings))
                    .map(t -> new ReportResponse<>(
                            ReportKey.COLLECTION_RATE.name(),
                            period,
                            t.getT1(),
                            t.getT2(),
                            Map.of(),
                            Map.of(),
                            List.copyOf(warnings),
                            OffsetDateTime.now()));
        });
    }

    @GetMapping("/export/excel")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.COLLECTION_RATE)
    @Operation(summary = "Download the collection-rate report as a multi-sheet XLSX",
            description = "Three sheets: Per Scheme / Per Group / Per Member. Warnings appear as a "
                        + "meta strip at the top of every sheet when populated.")
    public Mono<ResponseEntity<byte[]>> exportExcel(
            @RequestParam String periodStart,
            @RequestParam String periodEnd,
            @RequestParam(required = false) String reportingCurrency,
            @AuthenticationPrincipal Jwt jwt) {
        ReportPeriod period = ReportPeriod.parseFromQueryParams(periodStart, periodEnd, null);
        String filename = "collection-rate-" + period.periodStart() + "-to-" + period.periodEnd() + ".xlsx";
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("periodStart", period.periodStart().toString());
        details.put("periodEnd",   period.periodEnd().toString());
        if (reportingCurrency != null && !reportingCurrency.isBlank()) {
            details.put("reportingCurrency", reportingCurrency);
        }
        List<String> warnings = new ArrayList<>();
        return collectionRateExcelService.workbook(period.periodStart(), period.periodEnd(), warnings)
                .flatMap(bytes -> Mono.deferContextual(ctx -> securityEventPublisher.publishDataAccess(
                                TenantContext.get(ctx),
                                AuditActor.id(jwt),
                                AuditActor.email(jwt),
                                ReportKey.COLLECTION_RATE.name(),
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
