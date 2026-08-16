package com.medfund.finance.controller;

import com.medfund.finance.dto.CreatePaymentRunRequest;
import com.medfund.finance.dto.PageResponse;
import com.medfund.finance.dto.PaymentRunFilterParams;
import com.medfund.finance.dto.PaymentRunItemResponse;
import com.medfund.finance.dto.PaymentRunResponse;
import com.medfund.finance.service.PaymentRunService;
import com.medfund.finance.service.PaymentRunWorkbookService;
import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.report.ReportKey;
import com.medfund.shared.report.RequiresReport;
import com.medfund.shared.security.Permissions;
import com.medfund.shared.security.RequiresPermission;
import com.medfund.shared.security.SecurityEventPublisher;
import com.medfund.shared.tenant.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payment-runs")
@Tag(name = "Payment Runs", description = "Batch payment run creation and execution")
@SecurityRequirement(name = "bearer-jwt")
public class PaymentRunController {

    private static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final PaymentRunService paymentRunService;
    private final PaymentRunWorkbookService workbookService;
    private final SecurityEventPublisher securityEventPublisher;

    public PaymentRunController(PaymentRunService paymentRunService,
                                PaymentRunWorkbookService workbookService,
                                SecurityEventPublisher securityEventPublisher) {
        this.paymentRunService = paymentRunService;
        this.workbookService = workbookService;
        this.securityEventPublisher = securityEventPublisher;
    }

    @GetMapping
    @RequiresReport(ReportKey.PAYMENT_RUNS)
    @Operation(summary = "List all payment runs (unpaginated — prefer /page)")
    public Flux<PaymentRunResponse> findAll() {
        return paymentRunService.findAll().map(PaymentRunResponse::from);
    }

    @GetMapping("/page")
    @RequiresReport(ReportKey.PAYMENT_RUNS)
    @Operation(summary = "Server-side paginated, sortable, filterable payment-runs list",
        description = "Feeds /tenant/finance/runs. Sortable keys: runNumber, status, "
                + "totalAmount, currencyCode, paymentCount, executedAt, createdAt.")
    @ApiResponse(responseCode = "200", description = "Page of payment runs")
    public Mono<PageResponse<PaymentRunResponse>> searchPaged(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String currencyCode,
            @RequestParam(required = false) String q,
            @RequestParam(required = false, defaultValue = "createdAt") String sortKey,
            @RequestParam(required = false, defaultValue = "desc") String sortDirection,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "50") int size) {
        var params = new PaymentRunFilterParams(status, currencyCode, q, sortKey, sortDirection, page, size);
        return paymentRunService.searchPaged(params).map(pageResp -> PageResponse.of(
                pageResp.content().stream().map(PaymentRunResponse::from).toList(),
                pageResp.total(), pageResp.page(), pageResp.size()));
    }

    @GetMapping("/{id}")
    @RequiresReport(ReportKey.PAYMENT_RUNS)
    @Operation(summary = "Get payment run by ID")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Payment run found"),
        @ApiResponse(responseCode = "404", description = "Payment run not found")
    })
    public Mono<PaymentRunResponse> findById(@PathVariable UUID id) {
        return paymentRunService.findById(id).map(PaymentRunResponse::from);
    }

    @GetMapping("/{id}/items")
    @RequiresReport(ReportKey.PAYMENT_RUN_ITEMS)
    @Operation(summary = "Get items for a payment run")
    public Flux<PaymentRunItemResponse> findItems(@PathVariable UUID id) {
        return paymentRunService.findItems(id).map(PaymentRunItemResponse::from);
    }

    @GetMapping("/{id}/export/excel")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.PAYMENT_RUN_WORKBOOK)
    @Operation(summary = "Download a payment run as a multi-sheet XLSX workbook",
            description = "One sheet per distinct currency present in the run's items (D7-1) plus a "
                        + "summary sheet with per-currency native totals and the total converted into "
                        + "the tenant reporting currency at the run's executed date (D7-2). A missing "
                        + "FX rate omits the converted row and adds a warning line instead of failing "
                        + "the export (D7-3). The download is recorded as a data-access audit event.")
    public Mono<ResponseEntity<byte[]>> exportWorkbook(@PathVariable UUID id,
                                                       @AuthenticationPrincipal Jwt jwt) {
        return paymentRunService.findById(id)
                .flatMap(run -> Mono.deferContextual(ctx ->
                        workbookService.workbook(run, parseTenantId(TenantContext.get(ctx)))
                                .flatMap(bytes -> securityEventPublisher.publishDataAccess(
                                                TenantContext.get(ctx),
                                                AuditActor.id(jwt),
                                                AuditActor.email(jwt),
                                                ReportKey.PAYMENT_RUN_WORKBOOK.name(),
                                                excelDetails(id, run.getRunNumber()))
                                        .thenReturn(bytes))
                                .map(bytes -> ResponseEntity.ok()
                                        .contentType(XLSX)
                                        .header(HttpHeaders.CONTENT_DISPOSITION,
                                                "attachment; filename=\""
                                                        + workbookFilename(run.getRunNumber()) + "\"")
                                        .body(bytes))));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a new payment run",
        description = "Creates a payment run in draft status with auto-generated run number")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Payment run created"),
        @ApiResponse(responseCode = "400", description = "Validation error")
    })
    public Mono<PaymentRunResponse> create(@Valid @RequestBody CreatePaymentRunRequest request, @AuthenticationPrincipal Jwt jwt) {
        return paymentRunService.create(request, AuditActor.id(jwt), AuditActor.email(jwt)).map(PaymentRunResponse::from);
    }

    @PostMapping("/{id}/approve")
    @Operation(summary = "Approve a draft payment run",
        description = "Approval gate before execute. Optional — execute also accepts draft runs.")
    public Mono<PaymentRunResponse> approve(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return paymentRunService.approve(id, AuditActor.id(jwt), AuditActor.email(jwt)).map(PaymentRunResponse::from);
    }

    @PostMapping("/{id}/execute")
    @Operation(summary = "Execute a payment run",
        description = "Transitions a draft or approved payment run through executing to executed")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Payment run executed"),
        @ApiResponse(responseCode = "404", description = "Payment run not found")
    })
    public Mono<PaymentRunResponse> execute(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return paymentRunService.execute(id, AuditActor.id(jwt), AuditActor.email(jwt)).map(PaymentRunResponse::from);
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel a payment run",
        description = "Allowed in draft, approved, or executing states. Once executed, posting a reversing run is the path.")
    public Mono<PaymentRunResponse> cancel(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return paymentRunService.cancel(id, AuditActor.id(jwt), AuditActor.email(jwt)).map(PaymentRunResponse::from);
    }

    private static UUID parseTenantId(String tenantIdStr) {
        if (tenantIdStr == null || tenantIdStr.isBlank()) return null;
        try { return UUID.fromString(tenantIdStr); } catch (IllegalArgumentException e) { return null; }
    }

    private static Map<String, Object> excelDetails(UUID runId, String runNumber) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("runId", runId.toString());
        if (runNumber != null) details.put("runNumber", runNumber);
        return details;
    }

    private static String workbookFilename(String runNumber) {
        String safe = runNumber != null && !runNumber.isBlank()
                ? runNumber.replaceAll("[^A-Za-z0-9._-]", "-") : "payment-run";
        return "payment-run-" + safe + ".xlsx";
    }
}
