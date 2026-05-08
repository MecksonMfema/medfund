package com.medfund.tenancy.controller;

import com.medfund.tenancy.dto.ExchangeRateResponse;
import com.medfund.tenancy.dto.RecordExchangeRateRequest;
import com.medfund.tenancy.exception.ExchangeRateNotFoundException;
import com.medfund.tenancy.service.ExchangeRateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/exchange-rates")
@RequiredArgsConstructor
@Tag(name = "Exchange Rates", description = "Date-stamped FX rate snapshots used for currency conversion")
@SecurityRequirement(name = "bearer-jwt")
public class ExchangeRateController {

    private final ExchangeRateService exchangeRateService;

    @GetMapping
    @Operation(summary = "Get the latest rate at a date",
            description = "Returns the most recent rate at or before the supplied date. " +
                    "Tenant-scoped rates take precedence over platform-wide rates.")
    @ApiResponse(responseCode = "200", description = "Rate found")
    @ApiResponse(responseCode = "404", description = "No rate available for the pair on or before the date")
    public Mono<ExchangeRateResponse> getLatest(
            @RequestParam String base,
            @RequestParam String quote,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) UUID tenantId) {
        return exchangeRateService.findLatest(base, quote, date, tenantId)
                .switchIfEmpty(Mono.error(new ExchangeRateNotFoundException(base, quote, date)))
                .map(ExchangeRateResponse::from);
    }

    @GetMapping("/history")
    @Operation(summary = "Get rate history for a pair within a date range")
    @ApiResponse(responseCode = "200", description = "History returned in reverse chronological order")
    public Flux<ExchangeRateResponse> history(
            @RequestParam String base,
            @RequestParam String quote,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID tenantId) {
        return exchangeRateService.history(base, quote, from, to, tenantId)
                .map(ExchangeRateResponse::from);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Record a new exchange rate",
            description = "Persists an immutable rate snapshot. Both currencies must exist in the master registry. " +
                    "If a tenant-scoped rate is supplied, it overrides platform-wide rates for that tenant.")
    @ApiResponse(responseCode = "201", description = "Rate recorded")
    public Mono<ExchangeRateResponse> record(@Valid @RequestBody RecordExchangeRateRequest body,
                                             @AuthenticationPrincipal Jwt jwt) {
        String actorId    = jwt != null ? jwt.getSubject() : "system";
        String actorEmail = jwt != null ? resolveActorEmail(jwt) : null;
        return exchangeRateService.recordRate(
                        body.baseCurrency(),
                        body.quoteCurrency(),
                        body.rate(),
                        body.rateDate(),
                        body.source(),
                        body.tenantId(),
                        actorId,
                        actorEmail)
                .map(ExchangeRateResponse::from);
    }

    private static String resolveActorEmail(Jwt jwt) {
        String email = jwt.getClaimAsString("email");
        return email != null ? email : jwt.getClaimAsString("preferred_username");
    }
}
