package com.medfund.contributions.controller;

import com.medfund.contributions.dto.BadDebtRow;
import com.medfund.contributions.dto.BadDebtResponse;
import com.medfund.contributions.dto.DebtorRow;
import com.medfund.contributions.dto.FlagBadDebtRequest;
import com.medfund.contributions.dto.GroupBalanceResponse;
import com.medfund.contributions.dto.MemberBalanceResponse;
import com.medfund.contributions.dto.PageResponse;
import com.medfund.contributions.service.BadDebtService;
import com.medfund.contributions.service.BadDebtsExcelService;
import com.medfund.contributions.service.BalanceService;
import com.medfund.contributions.service.DebtorsExcelService;
import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.report.ReportKey;
import com.medfund.shared.report.RequiresReport;
import com.medfund.shared.security.Permissions;
import com.medfund.shared.security.RequiresPermission;
import com.medfund.shared.security.SecurityEventPublisher;
import com.medfund.shared.tenant.TenantContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/billing/balances")
@RequiredArgsConstructor
@Tag(name = "Balances",
        description = "Running balances per (member|group, currency). Drives the debtors list, bad-debt aging, and group-charge views.")
@SecurityRequirement(name = "bearer-jwt")
public class BalanceController {

    private static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final BalanceService balanceService;
    private final BadDebtService badDebtService;
    private final DebtorsExcelService debtorsExcelService;
    private final BadDebtsExcelService badDebtsExcelService;
    private final SecurityEventPublisher securityEventPublisher;

    @GetMapping("/members/{memberId}")
    @RequiresPermission(Permissions.BILLING_VIEW_DEBTORS)
    @RequiresReport(ReportKey.MEMBER_BALANCE)
    @Operation(summary = "Get a member's running balance for a currency",
            description = "Returns zeros if no balance row exists yet (member has not been billed in this currency).")
    @ApiResponse(responseCode = "200", description = "Balance returned")
    public Mono<MemberBalanceResponse> getMemberBalance(
            @PathVariable UUID memberId,
            @Parameter(description = "ISO 4217 currency code") @RequestParam String currency) {
        return balanceService.getMemberBalance(memberId, currency);
    }

    @GetMapping("/groups/{groupId}")
    @RequiresPermission(Permissions.BILLING_VIEW_DEBTORS)
    @RequiresReport(ReportKey.GROUP_BALANCE)
    @Operation(summary = "Get a group's running balance for a currency")
    public Mono<GroupBalanceResponse> getGroupBalance(
            @PathVariable UUID groupId,
            @RequestParam String currency) {
        return balanceService.getGroupBalance(groupId, currency);
    }

    @GetMapping("/debtors")
    @RequiresPermission(Permissions.BILLING_VIEW_DEBTORS)
    @RequiresReport(ReportKey.DEBTORS_LIST)
    @Operation(summary = "List members and groups with outstanding balances (debtors)",
            description = "Server-side paginated. Only currently-billable subjects "
                    + "(status IN active/suspended) appear. Filters: currency (required), "
                    + "subjectType (MEMBER = ungrouped individuals only; GROUP = groups only; "
                    + "omit for both), q (substring match on name/email/code).")
    public Mono<PageResponse<DebtorRow>> listDebtors(
            @RequestParam String currency,
            @RequestParam(required = false) String subjectType,
            @RequestParam(required = false) String q,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "20") int size) {
        return balanceService.listDebtors(currency, subjectType, q,
                Math.max(page, 0), Math.min(Math.max(size, 1), 100));
    }

    @GetMapping("/debtors/export/excel")
    @RequiresPermission(Permissions.BILLING_VIEW_DEBTORS)
    @RequiresReport(ReportKey.DEBTORS_LIST)
    @Operation(summary = "Download the debtors list as XLSX",
            description = "Same filter shape as the JSON /debtors endpoint; returns an .xlsx workbook "
                    + "with a header block (currency, subject-type filter, search term, export date, "
                    + "row count) and a table of every matching row up to a 10,000-row ceiling.")
    public Mono<ResponseEntity<byte[]>> exportDebtorsExcel(
            @RequestParam String currency,
            @RequestParam(required = false) String subjectType,
            @RequestParam(required = false) String q,
            @AuthenticationPrincipal Jwt jwt) {
        String filename = "debtors-" + currency + "-" + java.time.LocalDate.now() + ".xlsx";
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("currency", currency);
        if (subjectType != null && !subjectType.isBlank()) details.put("subjectType", subjectType);
        if (q != null && !q.isBlank()) details.put("q", q);
        return debtorsExcelService.generate(currency, subjectType, q)
                .flatMap(bytes -> Mono.deferContextual(ctx -> securityEventPublisher.publishDataAccess(
                                TenantContext.get(ctx),
                                AuditActor.id(jwt),
                                AuditActor.email(jwt),
                                ReportKey.DEBTORS_LIST.name(),
                                details))
                        .thenReturn(bytes))
                .map(bytes -> ResponseEntity.ok()
                        .contentType(XLSX)
                        .header(HttpHeaders.CONTENT_DISPOSITION,
                                "attachment; filename=\"" + filename + "\"")
                        .body(bytes));
    }

    @GetMapping("/bad-debts")
    @RequiresPermission(Permissions.BILLING_VIEW_DEBTORS)
    @RequiresReport(ReportKey.BAD_DEBTS)
    @Operation(summary = "List deactivated / terminated subjects with an outstanding balance",
            description = "Server-side paginated. Only subjects that are no longer billable "
                    + "(status IN deactivated/terminated) AND whose running balance is > 0 are returned "
                    + "— i.e. money we're owed by someone we can no longer bill. Filters mirror /debtors: "
                    + "currency (required), subjectType (MEMBER = ungrouped individuals only; GROUP = groups "
                    + "only; omit for both), q (substring match on name/email/code).")
    public Mono<PageResponse<DebtorRow>> listBadDebts(
            @RequestParam String currency,
            @RequestParam(required = false) String subjectType,
            @RequestParam(required = false) String q,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "20") int size) {
        return balanceService.listBadDebts(currency, subjectType, q,
                Math.max(page, 0), Math.min(Math.max(size, 1), 100));
    }

    @GetMapping("/bad-debts/export/excel")
    @RequiresPermission(Permissions.BILLING_VIEW_DEBTORS)
    @RequiresReport(ReportKey.BAD_DEBTS)
    @Operation(summary = "Download the bad-debts list as XLSX",
            description = "Same filter shape as the JSON /bad-debts endpoint; returns an .xlsx workbook "
                    + "with a header block (currency, subject-type filter, status filter description, search "
                    + "term, export date, row count) and a table of every matching row up to a 10,000-row "
                    + "ceiling.")
    public Mono<ResponseEntity<byte[]>> exportBadDebtsExcel(
            @RequestParam String currency,
            @RequestParam(required = false) String subjectType,
            @RequestParam(required = false) String q,
            @AuthenticationPrincipal Jwt jwt) {
        String filename = "bad-debts-" + currency + "-" + java.time.LocalDate.now() + ".xlsx";
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("currency", currency);
        if (subjectType != null && !subjectType.isBlank()) details.put("subjectType", subjectType);
        if (q != null && !q.isBlank()) details.put("q", q);
        return badDebtsExcelService.generate(currency, subjectType, q)
                .flatMap(bytes -> Mono.deferContextual(ctx -> securityEventPublisher.publishDataAccess(
                                TenantContext.get(ctx),
                                AuditActor.id(jwt),
                                AuditActor.email(jwt),
                                ReportKey.BAD_DEBTS.name(),
                                details))
                        .thenReturn(bytes))
                .map(bytes -> ResponseEntity.ok()
                        .contentType(XLSX)
                        .header(HttpHeaders.CONTENT_DISPOSITION,
                                "attachment; filename=\"" + filename + "\"")
                        .body(bytes));
    }

    @GetMapping("/aged-balances")
    @RequiresPermission(Permissions.BILLING_VIEW_DEBTORS)
    @RequiresReport(ReportKey.AGED_BALANCES)
    @Operation(summary = "List aged balances (still-billable subjects)",
            description = "Returns balances older than minAgeDays (defaults to dunning_config.suspension_days). " +
                    "Each row carries an aging classification (GRACE / SUSPENDED / WRITE_OFF). " +
                    "This is the collections-lifecycle view — for subjects that already fell off the billing " +
                    "roster with money still owing, see /bad-debts.")
    public Mono<PageResponse<BadDebtRow>> listAged(
            @RequestParam String currency,
            @RequestParam(required = false) Integer minAgeDays,
            @RequestParam(required = false) String q,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "20") int size) {
        return balanceService.listAged(currency, minAgeDays, q, Math.max(page, 0), Math.min(Math.max(size, 1), 100));
    }

    @PostMapping("/bad-debts/flag")
    @RequiresPermission(Permissions.BILLING_MANAGE_BAD_DEBTS)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Flag a contribution as bad debt",
            description = "Persists a bad_debts row in FLAGGED status via the existing BadDebtService state machine. " +
                    "The aged-balances list shows the classification regardless; this endpoint records the formal flagging decision.")
    @ApiResponse(responseCode = "201", description = "Bad debt flagged")
    public Mono<BadDebtResponse> flag(@Valid @RequestBody FlagBadDebtRequest body,
                                      @AuthenticationPrincipal Jwt jwt) {
        return badDebtService.flagAsOverdue(body.contributionId(), body.reason(),
                        AuditActor.id(jwt), AuditActor.email(jwt))
                .map(BadDebtResponse::from);
    }
}
