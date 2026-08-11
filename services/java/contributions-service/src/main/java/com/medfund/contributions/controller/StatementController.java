package com.medfund.contributions.controller;

import com.medfund.contributions.dto.StatementResponse;
import com.medfund.contributions.service.StatementExcelService;
import com.medfund.contributions.service.StatementService;
import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.report.ReportKey;
import com.medfund.shared.security.SecurityEventPublisher;
import com.medfund.shared.tenant.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
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
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/statements")
@RequiredArgsConstructor
@Tag(name = "Statements",
        description = "Member and group ledger statements: opening balance + chronological lines + closing balance.")
@SecurityRequirement(name = "bearer-jwt")
public class StatementController {

    private static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final StatementService statementService;
    private final StatementExcelService statementExcelService;
    private final SecurityEventPublisher securityEventPublisher;

    @GetMapping
    @Operation(summary = "Generate a contribution statement",
            description = "Builds a statement for a member or group across the supplied date range. " +
                    "If currency is omitted, picks the first currency seen on the target's contributions.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Statement generated"),
            @ApiResponse(responseCode = "400", description = "Invalid filters")
    })
    public Mono<StatementResponse> generate(
            @Parameter(description = "GROUP or MEMBER") @RequestParam String targetType,
            @RequestParam UUID targetId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodStart,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodEnd,
            @RequestParam(required = false) String currency) {
        return statementService.generate(targetType, targetId, periodStart, periodEnd, currency);
    }

    @GetMapping("/export/excel")
    @Operation(summary = "Generate a contribution statement as XLSX",
            description = "Same filters as the JSON endpoint; streams an .xlsx workbook with a header summary, " +
                    "ledger table, and totals.")
    public Mono<ResponseEntity<byte[]>> exportExcel(
            @RequestParam String targetType,
            @RequestParam UUID targetId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodStart,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodEnd,
            @RequestParam(required = false) String currency,
            @AuthenticationPrincipal Jwt jwt) {
        String filename = "statement-" + targetId + "-" + periodStart + "-" + periodEnd + ".xlsx";
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("targetType", targetType);
        details.put("targetId", targetId.toString());
        details.put("periodStart", periodStart.toString());
        details.put("periodEnd", periodEnd.toString());
        if (currency != null && !currency.isBlank()) details.put("currency", currency);
        ReportKey key = "GROUP".equalsIgnoreCase(targetType)
                ? ReportKey.GROUP_STATEMENT : ReportKey.MEMBER_STATEMENT;
        return statementExcelService.generate(targetType, targetId, periodStart, periodEnd, currency)
                .flatMap(bytes -> Mono.deferContextual(ctx -> securityEventPublisher.publishDataAccess(
                                TenantContext.get(ctx),
                                AuditActor.id(jwt),
                                AuditActor.email(jwt),
                                key.name(),
                                details))
                        .thenReturn(bytes))
                .map(bytes -> ResponseEntity.ok()
                        .contentType(XLSX)
                        .header(HttpHeaders.CONTENT_DISPOSITION,
                                "attachment; filename=\"" + filename + "\"")
                        .body(bytes));
    }
}
