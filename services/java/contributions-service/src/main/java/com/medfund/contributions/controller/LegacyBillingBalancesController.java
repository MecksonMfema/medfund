package com.medfund.contributions.controller;

import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * 410 Gone shim for the pre-rename billing/balances/creditors paths.
 * The word "creditor" was misused on the contributions side — from the
 * fund's POV, subjects with a running balance owe the fund and are
 * therefore debtors. The endpoints moved to /debtors in the same
 * release; this shim exists for one release so any stale FE build or
 * cached mobile client gets a clear, actionable error message rather
 * than a bare 404.
 *
 * <p>Delete after the next release cycle once telemetry confirms the
 * old paths are no longer hit.
 */
@RestController
@RequestMapping("/api/v1/billing/balances")
@RequiredArgsConstructor
@Hidden
public class LegacyBillingBalancesController {

    private static final String MOVED_MSG =
            "This endpoint has moved to %s. See release notes 2026-08-10.";

    @GetMapping("/creditors")
    public Mono<Void> creditorsGone() {
        return Mono.error(new ResponseStatusException(HttpStatus.GONE,
                String.format(MOVED_MSG, "/api/v1/billing/balances/debtors")));
    }

    @GetMapping("/creditors/export/excel")
    public Mono<Void> creditorsExcelGone() {
        return Mono.error(new ResponseStatusException(HttpStatus.GONE,
                String.format(MOVED_MSG, "/api/v1/billing/balances/debtors/export/excel")));
    }
}
