package com.medfund.finance.controller;

import com.medfund.finance.dto.PageResponse;
import com.medfund.finance.dto.ProviderBalanceFilterParams;
import com.medfund.finance.dto.ProviderBalanceResponse;
import com.medfund.finance.dto.ProviderBalanceRow;
import com.medfund.finance.service.ProviderBalanceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/provider-balances")
@Tag(name = "Provider Balances", description = "Provider balance tracking — claimed, approved, paid, outstanding")
@SecurityRequirement(name = "bearer-jwt")
public class ProviderBalanceController {

    private final ProviderBalanceService providerBalanceService;

    public ProviderBalanceController(ProviderBalanceService providerBalanceService) {
        this.providerBalanceService = providerBalanceService;
    }

    @GetMapping
    @Operation(summary = "List all provider balances (unpaginated — prefer /page)")
    public Flux<ProviderBalanceResponse> findAll() {
        return providerBalanceService.findAllByOutstandingBalance().map(ProviderBalanceResponse::from);
    }

    @GetMapping("/page")
    @Operation(summary = "Server-side paginated, sortable, filterable provider-balances list",
        description = "Feeds /tenant/finance/creditors/provider. Provider name joined.")
    @ApiResponse(responseCode = "200", description = "Page of provider balances")
    public Mono<PageResponse<ProviderBalanceRow>> searchPaged(
            @RequestParam(required = false) String currencyCode,
            @RequestParam(required = false) String q,
            @RequestParam(required = false, defaultValue = "outstandingBalance") String sortKey,
            @RequestParam(required = false, defaultValue = "desc") String sortDirection,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "50") int size) {
        var params = new ProviderBalanceFilterParams(currencyCode, q, sortKey, sortDirection, page, size);
        return providerBalanceService.searchPaged(params);
    }

    @GetMapping("/provider/{providerId}")
    @Operation(summary = "Get balance for a specific provider")
    public Mono<ProviderBalanceResponse> findByProviderId(@PathVariable UUID providerId) {
        return providerBalanceService.findByProviderId(providerId).map(ProviderBalanceResponse::from);
    }
}
