package com.medfund.finance.controller;

import com.medfund.finance.dto.CreditorFilterParams;
import com.medfund.finance.dto.CreditorRow;
import com.medfund.finance.dto.MemberBalanceResponse;
import com.medfund.finance.dto.PageResponse;
import com.medfund.finance.dto.ProviderBalanceResponse;
import com.medfund.finance.service.CreditorService;
import com.medfund.shared.security.Permissions;
import com.medfund.shared.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Unified list of parties the fund owes money for approved claims —
 * providers and members side by side. Consumed by
 * {@code /tenant/finance/creditors} in the Angular app.
 *
 * <p>Detail endpoints delegate to the per-subject-type services (which
 * already own the identity + summary logic), and the Excel export runs
 * over the same UNION query the paginated list uses so an offline copy
 * mirrors the on-screen state exactly.
 */
@RestController
@RequestMapping("/api/v1/creditors")
@RequiredArgsConstructor
@Tag(name = "Creditors",
        description = "Unified list of parties the fund owes for approved claims — providers and members.")
@SecurityRequirement(name = "bearer-jwt")
public class CreditorController {

    private static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final CreditorService service;

    @GetMapping("/page")
    @RequiresPermission(Permissions.FINANCE_VIEW_CREDITORS)
    @Operation(summary = "Paginated, sortable, filterable unified creditors list",
            description = "Feeds /tenant/finance/creditors. subjectType=PROVIDER|MEMBER|BOTH; "
                    + "sortable keys: subjectName, subjectCode, totalClaimed, totalApproved, "
                    + "totalPaid, outstandingBalance, currencyCode, lastActivityAt.")
    @ApiResponse(responseCode = "200", description = "Page of creditor rows")
    public Mono<PageResponse<CreditorRow>> searchPaged(
            @RequestParam(required = false) String subjectType,
            @RequestParam(required = false) String currencyCode,
            @RequestParam(required = false) String q,
            @RequestParam(required = false, defaultValue = "outstandingBalance") String sortKey,
            @RequestParam(required = false, defaultValue = "desc") String sortDirection,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "50") int size) {
        var params = new CreditorFilterParams(
                subjectType, currencyCode, q, sortKey, sortDirection, page, size);
        return service.searchPaged(params);
    }

    @GetMapping("/provider/{providerId}")
    @RequiresPermission(Permissions.FINANCE_VIEW_CREDITORS)
    @Operation(summary = "Provider creditor detail",
            description = "Delegates to ProviderBalanceService.findByProviderId — same shape as the "
                    + "pre-Phase-4 GET /provider-balances/provider/{id}.")
    public Mono<ProviderBalanceResponse> providerDetail(@PathVariable UUID providerId) {
        return service.providerDetail(providerId);
    }

    @GetMapping("/member/{memberId}")
    @RequiresPermission(Permissions.FINANCE_VIEW_CREDITORS)
    @Operation(summary = "Member creditor detail",
            description = "One row per currency the member carries a balance in; empty flux when the "
                    + "member has no balance row yet.")
    public Flux<MemberBalanceResponse> memberDetail(@PathVariable UUID memberId) {
        return service.memberDetail(memberId);
    }

    @GetMapping("/export/excel")
    @RequiresPermission(Permissions.FINANCE_VIEW_CREDITORS)
    @Operation(summary = "Excel export of the unified Creditors list",
            description = "Same filter shape as GET /page; the workbook mirrors what the operator sees "
                    + "on-screen. 10,000-row ceiling.")
    public Mono<ResponseEntity<byte[]>> exportExcel(
            @RequestParam(required = false) String subjectType,
            @RequestParam(required = false) String currencyCode,
            @RequestParam(required = false) String q) {
        String subjectSlug = subjectType != null && !subjectType.isBlank()
                ? subjectType.toUpperCase() : "BOTH";
        String currencySlug = currencyCode != null && !currencyCode.isBlank() ? currencyCode : "ALL";
        String filename = "creditors-" + subjectSlug + "-" + currencySlug + "-" + LocalDate.now() + ".xlsx";
        return service.exportExcel(subjectType, currencyCode, q)
                .map(bytes -> ResponseEntity.ok()
                        .contentType(XLSX)
                        .header(HttpHeaders.CONTENT_DISPOSITION,
                                "attachment; filename=\"" + filename + "\"")
                        .body(bytes));
    }
}
