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

    private final BalanceService balanceService;
    private final BadDebtService badDebtService;

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
            description = "Server-side paginated; filters: currency (required), q (substring match on name/email/code).")
    public Mono<PageResponse<CreditorRow>> listCreditors(
            @RequestParam String currency,
            @RequestParam(required = false) String q,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "20") int size) {
        return balanceService.listCreditors(currency, q, Math.max(page, 0), Math.min(Math.max(size, 1), 100));
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
        String actorId = jwt != null ? jwt.getSubject() : "system";
        return badDebtService.flagAsOverdue(body.contributionId(), body.reason(), actorId)
                .map(BadDebtResponse::from);
    }
}
