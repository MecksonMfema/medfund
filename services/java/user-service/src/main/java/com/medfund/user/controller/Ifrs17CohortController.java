package com.medfund.user.controller;

import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.security.RequiresPermission;
import com.medfund.user.dto.CreateIfrs17CohortRequest;
import com.medfund.user.dto.Ifrs17CohortResponse;
import com.medfund.user.dto.UpdateIfrs17CohortRequest;
import com.medfund.user.service.Ifrs17CohortService;
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
@RequestMapping("/api/v1/underwriting/cohorts")
@Tag(name = "Underwriting — IFRS 17 Cohorts",
     description = "Manage IFRS 17 cohort dimension (portfolio × year × type)")
@SecurityRequirement(name = "bearer-jwt")
@RequiredArgsConstructor
public class Ifrs17CohortController {

    private final Ifrs17CohortService service;

    @GetMapping
    @Operation(summary = "List IFRS 17 cohorts",
               description = "Active cohorts by default; pass includeInactive=true to see soft-deleted rows. Filter by portfolioId to scope.")
    public Flux<Ifrs17CohortResponse> findAll(@RequestParam(defaultValue = "false") boolean includeInactive,
                                              @RequestParam(required = false) UUID portfolioId) {
        if (portfolioId != null) {
            return service.findByPortfolio(portfolioId).map(Ifrs17CohortResponse::from);
        }
        return service.findAll(includeInactive).map(Ifrs17CohortResponse::from);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get an IFRS 17 cohort by ID")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Cohort found"),
        @ApiResponse(responseCode = "404", description = "Cohort not found")
    })
    public Mono<Ifrs17CohortResponse> findById(@PathVariable UUID id) {
        return service.findById(id).map(Ifrs17CohortResponse::from);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission("underwriting.cohort:manage")
    @Operation(summary = "Create an IFRS 17 cohort")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Cohort created"),
        @ApiResponse(responseCode = "400", description = "Validation error"),
        @ApiResponse(responseCode = "403", description = "Missing underwriting.cohort:manage"),
        @ApiResponse(responseCode = "404", description = "Parent portfolio not found"),
        @ApiResponse(responseCode = "409", description = "Duplicate (portfolio, year, type)")
    })
    public Mono<Ifrs17CohortResponse> create(@Valid @RequestBody CreateIfrs17CohortRequest request,
                                             @AuthenticationPrincipal Jwt jwt) {
        return service.create(request, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(Ifrs17CohortResponse::from);
    }

    @PutMapping("/{id}")
    @RequiresPermission("underwriting.cohort:manage")
    @Operation(summary = "Update an IFRS 17 cohort")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Cohort updated"),
        @ApiResponse(responseCode = "404", description = "Cohort not found"),
        @ApiResponse(responseCode = "403", description = "Missing underwriting.cohort:manage"),
        @ApiResponse(responseCode = "409", description = "Duplicate (portfolio, year, type)")
    })
    public Mono<Ifrs17CohortResponse> update(@PathVariable UUID id,
                                             @Valid @RequestBody UpdateIfrs17CohortRequest request,
                                             @AuthenticationPrincipal Jwt jwt) {
        return service.update(id, request, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(Ifrs17CohortResponse::from);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission("underwriting.cohort:manage")
    @Operation(summary = "Soft-delete an IFRS 17 cohort",
               description = "Sets is_active=false. Refuses (409) if any policies still reference this cohort.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Cohort soft-deleted"),
        @ApiResponse(responseCode = "404", description = "Cohort not found"),
        @ApiResponse(responseCode = "403", description = "Missing underwriting.cohort:manage"),
        @ApiResponse(responseCode = "409", description = "Cannot delete — policies still reference this cohort")
    })
    public Mono<Void> delete(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return service.softDelete(id, AuditActor.id(jwt), AuditActor.email(jwt));
    }
}
