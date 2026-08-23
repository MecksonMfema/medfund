package com.medfund.user.controller;

import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.security.RequiresPermission;
import com.medfund.user.dto.CreateIfrs17PortfolioRequest;
import com.medfund.user.dto.Ifrs17PortfolioResponse;
import com.medfund.user.dto.UpdateIfrs17PortfolioRequest;
import com.medfund.user.service.Ifrs17PortfolioService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
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
@RequestMapping("/api/v1/underwriting/portfolios")
@Tag(name = "Underwriting — IFRS 17 Portfolios",
     description = "Manage IFRS 17 portfolio dimension used by Phase 12 underwriting reports")
@SecurityRequirement(name = "bearer-jwt")
@RequiredArgsConstructor
public class Ifrs17PortfolioController {

    private final Ifrs17PortfolioService service;

    @GetMapping
    @Operation(summary = "List IFRS 17 portfolios",
               description = "Active portfolios by default; pass includeInactive=true to see soft-deleted rows.")
    public Flux<Ifrs17PortfolioResponse> findAll(@RequestParam(defaultValue = "false") boolean includeInactive) {
        return service.findAll(includeInactive).map(Ifrs17PortfolioResponse::from);
    }

    @GetMapping("/search")
    @Operation(summary = "Search portfolios by name (used by the cohort form picker)")
    public Flux<Ifrs17PortfolioResponse> search(@RequestParam(required = false, defaultValue = "") String q,
                                                @RequestParam(defaultValue = "10") int limit) {
        return service.search(q, limit).map(Ifrs17PortfolioResponse::from);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get an IFRS 17 portfolio by ID")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Portfolio found"),
        @ApiResponse(responseCode = "404", description = "Portfolio not found")
    })
    public Mono<Ifrs17PortfolioResponse> findById(@PathVariable UUID id) {
        return service.findById(id).map(Ifrs17PortfolioResponse::from);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission("underwriting.portfolio:manage")
    @Operation(summary = "Create an IFRS 17 portfolio")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Portfolio created"),
        @ApiResponse(responseCode = "400", description = "Validation error"),
        @ApiResponse(responseCode = "403", description = "Missing underwriting.portfolio:manage"),
        @ApiResponse(responseCode = "409", description = "Portfolio name already exists")
    })
    public Mono<Ifrs17PortfolioResponse> create(@Valid @RequestBody CreateIfrs17PortfolioRequest request,
                                                @AuthenticationPrincipal Jwt jwt) {
        return service.create(request, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(Ifrs17PortfolioResponse::from);
    }

    @PutMapping("/{id}")
    @RequiresPermission("underwriting.portfolio:manage")
    @Operation(summary = "Update an IFRS 17 portfolio")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Portfolio updated"),
        @ApiResponse(responseCode = "404", description = "Portfolio not found"),
        @ApiResponse(responseCode = "403", description = "Missing underwriting.portfolio:manage"),
        @ApiResponse(responseCode = "409", description = "Name conflict")
    })
    public Mono<Ifrs17PortfolioResponse> update(@PathVariable UUID id,
                                                @Valid @RequestBody UpdateIfrs17PortfolioRequest request,
                                                @AuthenticationPrincipal Jwt jwt) {
        return service.update(id, request, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(Ifrs17PortfolioResponse::from);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission("underwriting.portfolio:manage")
    @Operation(summary = "Soft-delete an IFRS 17 portfolio",
               description = "Sets is_active=false. Refuses (409) if any policies still reference this portfolio.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Portfolio soft-deleted"),
        @ApiResponse(responseCode = "404", description = "Portfolio not found"),
        @ApiResponse(responseCode = "403", description = "Missing underwriting.portfolio:manage"),
        @ApiResponse(responseCode = "409", description = "Cannot delete — policies still reference this portfolio")
    })
    public Mono<Void> delete(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return service.softDelete(id, AuditActor.id(jwt), AuditActor.email(jwt));
    }
}
