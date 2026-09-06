package com.medfund.tenancy.controller;

import com.medfund.tenancy.dto.UnsubscribeRequest;
import com.medfund.tenancy.dto.UnsubscribeResponse;
import com.medfund.tenancy.service.TenantReportScheduleRecipientService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Public unsubscribe endpoint — gated by URL-embedded UUID token, no JWT.
 * The gateway allows this path through unauthenticated
 * (see Phase 7 §7.3 middleware update). Response deliberately does NOT
 * distinguish "unknown token" from any other failure state to avoid leaking
 * recipient existence to a scanner.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/report-schedule-recipients/unsubscribe")
@RequiredArgsConstructor
@Tag(name = "Report Schedule Unsubscribe",
     description = "Public unsubscribe path - no JWT required, token-gated.")
public class ReportScheduleUnsubscribeController {

    private final TenantReportScheduleRecipientService service;

    @PostMapping("/{token}")
    @Operation(summary = "Unsubscribe a recipient by token (public, JWT bypass)")
    @ApiResponse(responseCode = "200",
            description = "Success flag + the deactivated email address on success; "
                        + "success=false, email=null on any failure (token unknown / expired).")
    public Mono<UnsubscribeResponse> unsubscribe(@PathVariable UUID token,
                                                 @RequestBody(required = false) UnsubscribeRequest body) {
        String reason = body != null ? body.reason() : null;
        return service.unsubscribeByToken(token, reason)
                .map(row -> new UnsubscribeResponse(true, row.getEmail()))
                .onErrorResume(NoSuchElementException.class, e -> {
                    log.info("[unsubscribe] token {} not found — returning generic failure", token);
                    return Mono.just(new UnsubscribeResponse(false, null));
                });
    }
}
