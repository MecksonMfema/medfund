package com.medfund.tenancy.controller;

import com.medfund.tenancy.dto.AddTenantCurrencyRequest;
import com.medfund.tenancy.dto.TenantCurrencyConfigResponse;
import com.medfund.tenancy.dto.UpdateTenantCurrencyRequest;
import com.medfund.tenancy.service.TenantCurrencyService;
import com.medfund.tenancy.service.TenantCurrencyService.AddCurrencyRequest;
import com.medfund.tenancy.service.TenantCurrencyService.UpdateCurrencyRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/currencies")
@RequiredArgsConstructor
@Tag(name = "Tenant Currencies", description = "Per-tenant currency configuration")
@SecurityRequirement(name = "bearer-jwt")
public class TenantCurrencyController {

    private final TenantCurrencyService tenantCurrencyService;

    @GetMapping
    @Operation(summary = "List currencies configured for a tenant")
    @ApiResponse(responseCode = "200", description = "Configured currencies returned")
    public Flux<TenantCurrencyConfigResponse> list(@PathVariable UUID tenantId) {
        return tenantCurrencyService.listForTenant(tenantId).map(TenantCurrencyConfigResponse::from);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add a currency to a tenant",
            description = "Currency must exist in public.currencies and not already be configured for the tenant.")
    @ApiResponse(responseCode = "201", description = "Currency added")
    public Mono<TenantCurrencyConfigResponse> add(@PathVariable UUID tenantId,
                                                  @Valid @RequestBody AddTenantCurrencyRequest body,
                                                  @AuthenticationPrincipal Jwt jwt) {
        String actorId    = jwt != null ? jwt.getSubject() : "system";
        String actorEmail = jwt != null ? resolveActorEmail(jwt) : null;
        var request = new AddCurrencyRequest(
                body.currencyCode(),
                body.isDefault(),
                body.isBillingCurrency(),
                body.isClaimsCurrency(),
                body.isPaymentCurrency(),
                body.exchangeRateSource()
        );
        return tenantCurrencyService.add(tenantId, request, actorId, actorEmail)
                .map(TenantCurrencyConfigResponse::from);
    }

    @PutMapping("/{configId}")
    @Operation(summary = "Update a tenant currency configuration",
            description = "Update flags or promote to default. Cannot deactivate the default currency.")
    @ApiResponse(responseCode = "200", description = "Currency updated")
    public Mono<TenantCurrencyConfigResponse> update(@PathVariable UUID tenantId,
                                                     @PathVariable UUID configId,
                                                     @Valid @RequestBody UpdateTenantCurrencyRequest body,
                                                     @AuthenticationPrincipal Jwt jwt) {
        String actorId    = jwt != null ? jwt.getSubject() : "system";
        String actorEmail = jwt != null ? resolveActorEmail(jwt) : null;
        var request = new UpdateCurrencyRequest(
                body.isDefault(),
                body.isActive(),
                body.isBillingCurrency(),
                body.isClaimsCurrency(),
                body.isPaymentCurrency(),
                body.exchangeRateSource()
        );
        return tenantCurrencyService.update(tenantId, configId, request, actorId, actorEmail)
                .map(TenantCurrencyConfigResponse::from);
    }

    @DeleteMapping("/{configId}")
    @Operation(summary = "Remove a currency from a tenant",
            description = "Cannot remove the default currency — promote another currency first.")
    @ApiResponse(responseCode = "204", description = "Currency removed")
    public Mono<ResponseEntity<Void>> remove(@PathVariable UUID tenantId,
                                             @PathVariable UUID configId,
                                             @AuthenticationPrincipal Jwt jwt) {
        String actorId    = jwt != null ? jwt.getSubject() : "system";
        String actorEmail = jwt != null ? resolveActorEmail(jwt) : null;
        return tenantCurrencyService.remove(tenantId, configId, actorId, actorEmail)
                .thenReturn(ResponseEntity.noContent().<Void>build());
    }

    private static String resolveActorEmail(Jwt jwt) {
        String email = jwt.getClaimAsString("email");
        return email != null ? email : jwt.getClaimAsString("preferred_username");
    }
}
