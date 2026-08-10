package com.medfund.finance.controller;

import com.medfund.finance.dto.TenantBankAccountResponse;
import com.medfund.finance.dto.UpsertTenantBankAccountRequest;
import com.medfund.finance.service.TenantBankAccountService;
import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.security.Permissions;
import com.medfund.shared.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tenant-bank-accounts")
@RequiredArgsConstructor
@Tag(name = "Tenant Bank Accounts",
     description = "The tenant's own bank accounts used for outbound disbursements and inbound receipt matching.")
@SecurityRequirement(name = "bearer-jwt")
public class TenantBankAccountController {

    private final TenantBankAccountService service;

    @GetMapping
    @Operation(summary = "List tenant bank accounts (filter by currency optional)")
    public Flux<TenantBankAccountResponse> list(@RequestParam(required = false) String currency) {
        return (currency != null ? service.findByCurrency(currency) : service.findAll())
            .map(TenantBankAccountResponse::from);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a tenant bank account")
    public Mono<TenantBankAccountResponse> get(@PathVariable UUID id) {
        return service.findById(id).map(TenantBankAccountResponse::from);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permissions.ADMIN_BANK_ACCOUNTS_MANAGE)
    @Operation(summary = "Add a new tenant bank account")
    public Mono<TenantBankAccountResponse> create(@Valid @RequestBody UpsertTenantBankAccountRequest request,
                                                  @AuthenticationPrincipal Jwt jwt) {
        return service.create(request, AuditActor.id(jwt), AuditActor.email(jwt))
            .map(TenantBankAccountResponse::from);
    }

    @PutMapping("/{id}")
    @RequiresPermission(Permissions.ADMIN_BANK_ACCOUNTS_MANAGE)
    @Operation(summary = "Update a tenant bank account")
    public Mono<TenantBankAccountResponse> update(@PathVariable UUID id,
                                                  @Valid @RequestBody UpsertTenantBankAccountRequest request,
                                                  @AuthenticationPrincipal Jwt jwt) {
        return service.update(id, request, AuditActor.id(jwt), AuditActor.email(jwt))
            .map(TenantBankAccountResponse::from);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(Permissions.ADMIN_BANK_ACCOUNTS_MANAGE)
    @Operation(summary = "Remove a tenant bank account")
    public Mono<Void> delete(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return service.delete(id, AuditActor.id(jwt), AuditActor.email(jwt));
    }
}
