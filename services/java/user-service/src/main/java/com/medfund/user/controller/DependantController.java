package com.medfund.user.controller;

import com.medfund.user.dto.CreateDependantRequest;
import com.medfund.user.dto.DependantResponse;
import com.medfund.user.dto.UpdateDependantRequest;
import com.medfund.user.service.DependantService;
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
@RequestMapping("/api/v1/dependants")
@Tag(name = "Dependants", description = "Manage member dependants")
@SecurityRequirement(name = "bearer-jwt")
public class DependantController {

    private final DependantService dependantService;

    public DependantController(DependantService dependantService) {
        this.dependantService = dependantService;
    }

    @GetMapping("/member/{memberId}")
    @Operation(summary = "List dependants for a member")
    public Flux<DependantResponse> findByMemberId(@PathVariable UUID memberId) {
        return dependantService.findByMemberId(memberId).map(DependantResponse::from);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get dependant by ID")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Dependant found"),
        @ApiResponse(responseCode = "404", description = "Dependant not found")
    })
    public Mono<DependantResponse> findById(@PathVariable UUID id) {
        return dependantService.findById(id).map(DependantResponse::from);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add a dependant to a member")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Dependant created"),
        @ApiResponse(responseCode = "400", description = "Validation error")
    })
    public Mono<DependantResponse> create(@Valid @RequestBody CreateDependantRequest request, Principal principal) {
        return dependantService.create(request, principal.getName()).map(DependantResponse::from);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update an existing dependant",
               description = "Partial update — null fields are left unchanged.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Dependant updated"),
        @ApiResponse(responseCode = "404", description = "Dependant not found")
    })
    public Mono<DependantResponse> update(@PathVariable UUID id,
                                          @Valid @RequestBody UpdateDependantRequest request,
                                          Principal principal) {
        return dependantService.update(id, request, principal.getName()).map(DependantResponse::from);
    }

    @PostMapping("/{id}/remove")
    @Operation(summary = "Remove a dependant", description = "Soft-removes dependant by setting status to 'removed'")
    public Mono<DependantResponse> remove(@PathVariable UUID id, Principal principal) {
        return dependantService.remove(id, principal.getName()).map(DependantResponse::from);
    }
}
