package com.medfund.finance.controller;

import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * 410 Gone shim for the pre-Phase-4 /api/v1/provider-balances paths.
 * The finance-side "Creditors" surface unified providers and members
 * under /api/v1/creditors in the same release; this shim exists for one
 * release so any stale FE build or scripted caller gets a clear,
 * actionable error message rather than a bare 404.
 *
 * <p>Delete after the next release cycle once telemetry confirms the
 * old paths are no longer hit.
 */
@RestController
@RequestMapping("/api/v1/provider-balances")
@RequiredArgsConstructor
@Hidden
public class LegacyProviderBalancesController {

    private static final String MOVED_MSG =
            "This endpoint has moved to %s. See release notes 2026-08-10.";

    @GetMapping
    public Mono<Void> listGone() {
        return Mono.error(new ResponseStatusException(HttpStatus.GONE,
                String.format(MOVED_MSG, "/api/v1/creditors/page?subjectType=PROVIDER")));
    }

    @GetMapping("/page")
    public Mono<Void> pageGone() {
        return Mono.error(new ResponseStatusException(HttpStatus.GONE,
                String.format(MOVED_MSG, "/api/v1/creditors/page?subjectType=PROVIDER")));
    }

    @GetMapping("/provider/{providerId}")
    public Mono<Void> providerDetailGone(@PathVariable UUID providerId) {
        return Mono.error(new ResponseStatusException(HttpStatus.GONE,
                String.format(MOVED_MSG, "/api/v1/creditors/provider/" + providerId)));
    }
}
