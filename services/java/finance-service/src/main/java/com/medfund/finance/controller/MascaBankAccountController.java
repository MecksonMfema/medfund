package com.medfund.finance.controller;

import com.medfund.finance.dto.MascaBankAccountResponse;
import com.medfund.finance.dto.UpsertMascaBankAccountRequest;
import com.medfund.finance.service.MascaBankAccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.security.Principal;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/masca-bank-accounts")
@RequiredArgsConstructor
@Tag(name = "MASCA Bank Accounts", description = "Platform-side bank accounts that receive payouts and statement credits.")
@SecurityRequirement(name = "bearer-jwt")
public class MascaBankAccountController {

    private final MascaBankAccountService service;

    @GetMapping
    @Operation(summary = "List all platform bank accounts (filter by currency optional)")
    public Flux<MascaBankAccountResponse> list(@RequestParam(required = false) String currency) {
        return (currency != null ? service.findByCurrency(currency) : service.findAll())
            .map(MascaBankAccountResponse::from);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a platform bank account")
    public Mono<MascaBankAccountResponse> get(@PathVariable UUID id) {
        return service.findById(id).map(MascaBankAccountResponse::from);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add a new platform bank account")
    public Mono<MascaBankAccountResponse> create(@Valid @RequestBody UpsertMascaBankAccountRequest request,
                                                  Principal principal) {
        return service.create(request, principal != null ? principal.getName() : null)
            .map(MascaBankAccountResponse::from);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a platform bank account")
    public Mono<MascaBankAccountResponse> update(@PathVariable UUID id,
                                                  @Valid @RequestBody UpsertMascaBankAccountRequest request,
                                                  Principal principal) {
        return service.update(id, request, principal != null ? principal.getName() : null)
            .map(MascaBankAccountResponse::from);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove a platform bank account")
    public Mono<Void> delete(@PathVariable UUID id, Principal principal) {
        return service.delete(id, principal != null ? principal.getName() : null);
    }
}
