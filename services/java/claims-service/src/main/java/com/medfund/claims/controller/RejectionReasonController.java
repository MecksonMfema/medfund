package com.medfund.claims.controller;

import com.medfund.claims.dto.RejectionReasonResponse;
import com.medfund.claims.dto.UpsertRejectionReasonRequest;
import com.medfund.claims.service.RejectionReasonService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.security.Principal;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/rejection-reasons")
@Tag(name = "Rejection Reasons",
        description = "Catalogue of R-codes used by the adjudication pipeline and manual rejection flows.")
@SecurityRequirement(name = "bearer-jwt")
public class RejectionReasonController {

    private final RejectionReasonService service;

    public RejectionReasonController(RejectionReasonService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "List all rejection reasons (active + inactive)")
    public Flux<RejectionReasonResponse> list(@RequestParam(required = false, defaultValue = "false") boolean activeOnly) {
        var stream = activeOnly ? service.findAllActive() : service.findAll();
        return stream.map(RejectionReasonResponse::from);
    }

    @GetMapping("/{code}")
    @Operation(summary = "Look up a rejection reason by code")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Reason found"),
            @ApiResponse(responseCode = "404", description = "Reason not found"),
    })
    public Mono<RejectionReasonResponse> get(@PathVariable String code) {
        return service.findByCode(code).map(RejectionReasonResponse::from);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a new rejection reason")
    public Mono<RejectionReasonResponse> create(@Valid @RequestBody UpsertRejectionReasonRequest request,
                                                  Principal principal) {
        return service.create(request, principal != null ? principal.getName() : null)
                .map(RejectionReasonResponse::from);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update an existing rejection reason (description / category / active flag)")
    public Mono<RejectionReasonResponse> update(@PathVariable UUID id,
                                                  @Valid @RequestBody UpsertRejectionReasonRequest request,
                                                  Principal principal) {
        return service.update(id, request, principal != null ? principal.getName() : null)
                .map(RejectionReasonResponse::from);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a rejection reason (use isActive=false instead unless certain)")
    public Mono<Void> delete(@PathVariable UUID id, Principal principal) {
        return service.delete(id, principal != null ? principal.getName() : null);
    }
}
