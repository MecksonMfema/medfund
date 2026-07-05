package com.medfund.contributions.controller;

import com.medfund.contributions.dto.BadDebtRow;
import com.medfund.contributions.dto.BadDebtResponse;
import com.medfund.contributions.dto.CreditorRow;
import com.medfund.contributions.dto.FlagBadDebtRequest;
import com.medfund.contributions.dto.GroupBalanceResponse;
import com.medfund.contributions.dto.MemberBalanceResponse;
import com.medfund.contributions.dto.PageResponse;
import com.medfund.contributions.service.BadDebtService;
import com.medfund.contributions.service.BalanceService;
import com.medfund.contributions.service.CreditorsExcelService;
import com.medfund.shared.audit.AuditActor;
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

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/billing/balances")
@RequiredArgsConstructor
@Tag(name = "Balances",
        description = "Running balances per (member|group, currency). Drives the creditor list, bad-debt aging, and group-charge views.")
@SecurityRequirement(name = "bearer-jwt")
public class BalanceController {

    private static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final BalanceService balanceService;
    private final BadDebtService badDebtService;
    private final CreditorsExcelService creditorsExcelService;

    @GetMapping("/members/{memberId}")
    @Operation(summary = "Get a member's running balance for a currency",
            description = "Returns zeros if no balance row exists yet (member has not been billed in this currency).")
    @ApiResponse(responseCode = "200", description = "Balance returned")
    public Mono<MemberBalanceResponse> getMemberBalance(
            @PathVariable UUID memberId,
            @Parameter(description = "ISO 4217 currency code") @RequestParam String currency) {
        return balanceService.getMemberBalance(memberId, currency);
    }

    @GetMapping("/groups/{groupId}")
    @Operation(summary = "Get a group's running balance for a currency")
    public Mono<GroupBalanceResponse> getGroupBalance(
            @PathVariable UUID groupId,
            @RequestParam String currency) {
        return balanceService.getGroupBalance(groupId, currency);
    }

    @GetMapping("/creditors")
    @Operation(summary = "List members and groups with outstanding balances",
            description = "Server-side paginated. Only currently-billable subjects "
                    + "(status IN active/suspended) appear. Filters: currency (required), "
                    + "subjectType (MEMBER = ungrouped individuals only; GROUP = groups only; "
                    + "omit for both), q (substring match on name/email/code).")
    public Mono<PageResponse<CreditorRow>> listCreditors(
            @RequestParam String currency,
            @RequestParam(required = false) String subjectType,
            @RequestParam(required = false) String q,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "20") int size) {
        return balanceService.listCreditors(currency, subjectType, q,
                Math.max(page, 0), Math.min(Math.max(size, 1), 100));
    }

    @GetMapping("/creditors/export/excel")
    @Operation(summary = "Download the creditors list as XLSX",
            description = "Same filter shape as the JSON /creditors endpoint; returns an .xlsx workbook "
                    + "with a header block (currency, subject-type filter, search term, export date, "
                    + "row count) and a table of every matching row up to a 10,000-row ceiling.")
    public Mono<ResponseEntity<byte[]>> exportCreditorsExcel(
            @RequestParam String currency,
            @RequestParam(required = false) String subjectType,
            @RequestParam(required = false) String q) {
        String filename = "creditors-" + currency + "-" + java.time.LocalDate.now() + ".xlsx";
        return creditorsExcelService.generate(currency, subjectType, q)
                .map(bytes -> ResponseEntity.ok()
                        .contentType(XLSX)
                        .header(HttpHeaders.CONTENT_DISPOSITION,
                                "attachment; filename=\"" + filename + "\"")
                        .body(bytes));
    }

    @GetMapping("/bad-debts")
    @Operation(summary = "List aged balances",
            description = "Returns balances older than minAgeDays (defaults to dunning_config.suspension_days). " +
                    "Each row carries an aging classification (GRACE / SUSPENDED / WRITE_OFF).")
    public Mono<PageResponse<BadDebtRow>> listAged(
            @RequestParam String currency,
            @RequestParam(required = false) Integer minAgeDays,
            @RequestParam(required = false) String q,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "20") int size) {
        return balanceService.listAged(currency, minAgeDays, q, Math.max(page, 0), Math.min(Math.max(size, 1), 100));
    }

    @PostMapping("/bad-debts/flag")
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
