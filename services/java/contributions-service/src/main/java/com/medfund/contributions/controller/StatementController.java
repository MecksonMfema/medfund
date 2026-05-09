package com.medfund.contributions.controller;

import com.medfund.contributions.dto.StatementResponse;
import com.medfund.contributions.service.StatementExcelService;
import com.medfund.contributions.service.StatementService;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
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
            @RequestParam(required = false) String currency) {
        String filename = "statement-" + targetId + "-" + periodStart + "-" + periodEnd + ".xlsx";
        return statementExcelService.generate(targetType, targetId, periodStart, periodEnd, currency)
                .map(bytes -> ResponseEntity.ok()
                        .contentType(XLSX)
                        .header(HttpHeaders.CONTENT_DISPOSITION,
                                "attachment; filename=\"" + filename + "\"")
                        .body(bytes));
    }
}
