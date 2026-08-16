package com.medfund.finance.controller;

import com.medfund.finance.dto.BalanceHistoryResponse;
import com.medfund.finance.service.BalanceHistoryExcelService;
import com.medfund.finance.service.BalanceHistoryService;
import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.report.ReportKey;
import com.medfund.shared.report.ReportResponse;
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
 * Phase 6 balance-history report — freeze-frame of a provider's or
 * member's balance at each executed payment run (V080). Periodless
 * (G20): the report is a current-state ledger trace, not a windowed
 * aggregate, so the envelope's {@code period} is null and rows are
 * native per-currency (G34). Optional {@code ?asAtRun=} pins the row
 * set to exactly one run (D6-4); {@code ?currency=} narrows to one
 * native currency.
 */
@RestController
@RequestMapping("/api/v1/reports/balance-history")
@RequiredArgsConstructor
@Tag(name = "Balance history",
        description = "Freeze-frame of provider / member balances at each executed payment run. "
                    + "Periodless current-state ledger trace (G20); rows native per-currency (G34).")
@SecurityRequirement(name = "bearer-jwt")
public class BalanceHistoryController {

    private static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final BalanceHistoryService historyService;
    private final BalanceHistoryExcelService excelService;
    private final SecurityEventPublisher securityEventPublisher;

    @GetMapping("/provider/{providerId}")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.PROVIDER_BALANCE_HISTORY)
    @Operation(summary = "Provider balance history — frozen per executed payment run",
            description = "One row per (run, currency), newest first: opening = closing = the live "
                        + "outstanding balance at run execution, plus the run's net due. "
                        + "?asAtRun={runId} pins to exactly one run; ?currency= narrows to one native "
                        + "currency. Periodless (G20); rows stay native (G34).")
    public Mono<ReportResponse<BalanceHistoryResponse>> providerHistory(
            @PathVariable UUID providerId,
            @RequestParam(required = false) UUID asAtRun,
            @RequestParam(required = false) String currency) {
        return Mono.zip(
                        historyService.providerHistory(providerId, asAtRun, currency),
                        historyService.providerPerCurrency(providerId))
                .map(t -> ReportResponse.of(
                        ReportKey.PROVIDER_BALANCE_HISTORY,
                        null,
                        "",
                        t.getT1(),
                        t.getT2(),
                        Map.of(),
                        List.of()));
    }

    @GetMapping("/provider/{providerId}/export/excel")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.PROVIDER_BALANCE_HISTORY)
    @Operation(summary = "Download the provider balance-history report as XLSX",
            description = "One sheet: one row per (run, currency) with the frozen ledger and the run's "
                        + "net due. The download is recorded as a data-access audit event.")
    public Mono<ResponseEntity<byte[]>> providerExcel(
            @PathVariable UUID providerId,
            @RequestParam(required = false) UUID asAtRun,
            @RequestParam(required = false) String currency,
            @AuthenticationPrincipal Jwt jwt) {
        String filename = "provider-balance-history-" + providerId + ".xlsx";
        return excelService.providerWorkbook(providerId, asAtRun, currency)
                .flatMap(bytes -> Mono.deferContextual(ctx -> securityEventPublisher.publishDataAccess(
                                TenantContext.get(ctx),
                                AuditActor.id(jwt),
                                AuditActor.email(jwt),
                                ReportKey.PROVIDER_BALANCE_HISTORY.name(),
                                excelDetails(providerId, asAtRun, currency)))
                        .thenReturn(bytes))
                .map(bytes -> ResponseEntity.ok()
                        .contentType(XLSX)
                        .header(HttpHeaders.CONTENT_DISPOSITION,
                                "attachment; filename=\"" + filename + "\"")
                        .body(bytes));
    }

    @GetMapping("/member/{memberId}")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.MEMBER_BALANCE_HISTORY)
    @Operation(summary = "Member balance history — frozen per executed payment run",
            description = "One row per (run, currency), newest first: opening = closing = the live "
                        + "outstanding balance at run execution, plus the run's net due. "
                        + "?asAtRun={runId} pins to exactly one run; ?currency= narrows to one native "
                        + "currency. Periodless (G20); rows stay native (G34).")
    public Mono<ReportResponse<BalanceHistoryResponse>> memberHistory(
            @PathVariable UUID memberId,
            @RequestParam(required = false) UUID asAtRun,
            @RequestParam(required = false) String currency) {
        return Mono.zip(
                        historyService.memberHistory(memberId, asAtRun, currency),
                        historyService.memberPerCurrency(memberId))
                .map(t -> ReportResponse.of(
                        ReportKey.MEMBER_BALANCE_HISTORY,
                        null,
                        "",
                        t.getT1(),
                        t.getT2(),
                        Map.of(),
                        List.of()));
    }

    @GetMapping("/member/{memberId}/export/excel")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.MEMBER_BALANCE_HISTORY)
    @Operation(summary = "Download the member balance-history report as XLSX",
            description = "One sheet: one row per (run, currency) with the frozen ledger and the run's "
                        + "net due. The download is recorded as a data-access audit event.")
    public Mono<ResponseEntity<byte[]>> memberExcel(
            @PathVariable UUID memberId,
            @RequestParam(required = false) UUID asAtRun,
            @RequestParam(required = false) String currency,
            @AuthenticationPrincipal Jwt jwt) {
        String filename = "member-balance-history-" + memberId + ".xlsx";
        return excelService.memberWorkbook(memberId, asAtRun, currency)
                .flatMap(bytes -> Mono.deferContextual(ctx -> securityEventPublisher.publishDataAccess(
                                TenantContext.get(ctx),
                                AuditActor.id(jwt),
                                AuditActor.email(jwt),
                                ReportKey.MEMBER_BALANCE_HISTORY.name(),
                                excelDetails(memberId, asAtRun, currency)))
                        .thenReturn(bytes))
                .map(bytes -> ResponseEntity.ok()
                        .contentType(XLSX)
                        .header(HttpHeaders.CONTENT_DISPOSITION,
                                "attachment; filename=\"" + filename + "\"")
                        .body(bytes));
    }

    private static Map<String, Object> excelDetails(UUID payeeId, UUID asAtRun, String currency) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("payeeId", payeeId.toString());
        if (asAtRun != null) details.put("asAtRun", asAtRun.toString());
        if (currency != null && !currency.isBlank()) details.put("currency", currency);
        return details;
    }
}
