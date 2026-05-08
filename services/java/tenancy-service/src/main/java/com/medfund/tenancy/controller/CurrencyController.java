package com.medfund.tenancy.controller;

import com.medfund.tenancy.dto.CurrencyResponse;
import com.medfund.tenancy.entity.Currency;
import com.medfund.tenancy.repository.CurrencyRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/v1/currencies")
@RequiredArgsConstructor
@Tag(name = "Currencies", description = "ISO 4217 master currency registry")
@SecurityRequirement(name = "bearer-jwt")
public class CurrencyController {

    private final CurrencyRepository currencyRepository;

    @GetMapping
    @Operation(summary = "List currencies",
            description = "Returns the master currency registry. Set active=true (default) to filter to active rows.")
    @ApiResponse(responseCode = "200", description = "Currencies returned")
    public Flux<CurrencyResponse> list(@RequestParam(required = false, defaultValue = "true") boolean active) {
        Flux<Currency> source = active ? currencyRepository.findAllActive() : currencyRepository.findAll();
        return source.map(CurrencyResponse::from);
    }
}
