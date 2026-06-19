package com.medfund.claims.controller;

import com.medfund.claims.dto.DrugResponse;
import com.medfund.claims.dto.UpsertDrugRequest;
import com.medfund.claims.service.DrugService;
import com.medfund.shared.audit.AuditActor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/drugs")
@Tag(name = "Drugs", description = "Drug catalogue (formulary) used by drug-claim submission and adjudication.")
@SecurityRequirement(name = "bearer-jwt")
public class DrugController {

    private final DrugService service;

    public DrugController(DrugService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "List drugs in the formulary")
    public Flux<DrugResponse> list(@RequestParam(required = false, defaultValue = "false") boolean activeOnly) {
        return service.findAll(activeOnly).map(DrugResponse::from);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a drug by id")
    public Mono<DrugResponse> get(@PathVariable UUID id) {
        return service.findById(id).map(DrugResponse::from);
    }

    @GetMapping("/search")
    @Operation(summary = "Search drugs by name")
    public Flux<DrugResponse> search(@RequestParam String q) {
        return service.search(q).map(DrugResponse::from);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add a drug to the formulary")
    public Mono<DrugResponse> create(@Valid @RequestBody UpsertDrugRequest request,
                                      @AuthenticationPrincipal Jwt jwt) {
        return service.create(request, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(DrugResponse::from);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a drug")
    public Mono<DrugResponse> update(@PathVariable UUID id,
                                       @Valid @RequestBody UpsertDrugRequest request,
                                       @AuthenticationPrincipal Jwt jwt) {
        return service.update(id, request, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(DrugResponse::from);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove a drug from the formulary")
    public Mono<Void> delete(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return service.delete(id, AuditActor.id(jwt), AuditActor.email(jwt));
    }
}
