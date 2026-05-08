package com.medfund.contributions.controller;

import com.medfund.contributions.dto.*;
import com.medfund.contributions.service.SchemeService;
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
@RequestMapping("/api/v1/schemes")
@Tag(name = "Schemes", description = "Insurance scheme, benefit, and age group management")
@SecurityRequirement(name = "bearer-jwt")
public class SchemeController {

    private final SchemeService schemeService;

    public SchemeController(SchemeService schemeService) {
        this.schemeService = schemeService;
    }

    @GetMapping
    @Operation(summary = "List all schemes")
    public Flux<SchemeResponse> findAll() {
        return schemeService.findAll().map(SchemeResponse::from);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get scheme by ID")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Scheme found"),
        @ApiResponse(responseCode = "404", description = "Scheme not found")
    })
    public Mono<SchemeResponse> findById(@PathVariable UUID id) {
        return schemeService.findById(id).map(SchemeResponse::from);
    }

    @GetMapping("/status/{status}")
    @Operation(summary = "List schemes by status")
    public Flux<SchemeResponse> findByStatus(@PathVariable String status) {
        return schemeService.findByStatus(status).map(SchemeResponse::from);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a new scheme")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Scheme created"),
        @ApiResponse(responseCode = "400", description = "Validation error"),
        @ApiResponse(responseCode = "409", description = "Scheme with this name already exists")
    })
    public Mono<SchemeResponse> create(@Valid @RequestBody CreateSchemeRequest request, Principal principal) {
        return schemeService.create(request, principal.getName()).map(SchemeResponse::from);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update an existing scheme")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Scheme updated"),
        @ApiResponse(responseCode = "404", description = "Scheme not found")
    })
    public Mono<SchemeResponse> update(@PathVariable UUID id,
                                       @Valid @RequestBody UpdateSchemeRequest request,
                                       Principal principal) {
        return schemeService.update(id, request, principal.getName()).map(SchemeResponse::from);
    }

    @PostMapping("/{id}/deactivate")
    @Operation(summary = "Deactivate a scheme")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Scheme deactivated"),
        @ApiResponse(responseCode = "404", description = "Scheme not found")
    })
    public Mono<SchemeResponse> deactivate(@PathVariable UUID id, Principal principal) {
        return schemeService.deactivate(id, principal.getName()).map(SchemeResponse::from);
    }

    @GetMapping("/{schemeId}/benefits")
    @Operation(summary = "List benefits for a scheme")
    public Flux<SchemeBenefitResponse> findBenefits(@PathVariable UUID schemeId) {
        return schemeService.findBenefitsBySchemeId(schemeId).map(SchemeBenefitResponse::from);
    }

    @PostMapping("/benefits")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a scheme benefit")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Scheme benefit created"),
        @ApiResponse(responseCode = "400", description = "Validation error")
    })
    public Mono<SchemeBenefitResponse> createBenefit(@Valid @RequestBody CreateSchemeBenefitRequest request,
                                                     Principal principal) {
        return schemeService.createBenefit(request, principal.getName()).map(SchemeBenefitResponse::from);
    }

    @GetMapping("/benefits/{id}")
    @Operation(summary = "Get a scheme benefit by id")
    public Mono<SchemeBenefitResponse> findBenefitById(@PathVariable UUID id) {
        return schemeService.findBenefitById(id).map(SchemeBenefitResponse::from);
    }

    @PutMapping("/benefits/{id}")
    @Operation(summary = "Update an existing scheme benefit",
        description = "Currency stays inherited from the parent scheme — pass it explicitly only as a defensive check.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Scheme benefit updated"),
        @ApiResponse(responseCode = "400", description = "Validation error or currency mismatch with parent scheme"),
        @ApiResponse(responseCode = "404", description = "Scheme benefit not found")
    })
    public Mono<SchemeBenefitResponse> updateBenefit(@PathVariable UUID id,
                                                     @Valid @RequestBody UpdateSchemeBenefitRequest request,
                                                     Principal principal) {
        return schemeService.updateBenefit(id, request, principal.getName()).map(SchemeBenefitResponse::from);
    }

    @DeleteMapping("/benefits/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a scheme benefit")
    @ApiResponse(responseCode = "204", description = "Scheme benefit deleted")
    public Mono<Void> deleteBenefit(@PathVariable UUID id, Principal principal) {
        return schemeService.deleteBenefit(id, principal.getName());
    }

    @GetMapping("/{schemeId}/age-groups")
    @Operation(summary = "List age groups for a scheme")
    public Flux<AgeGroupResponse> findAgeGroups(@PathVariable UUID schemeId) {
        return schemeService.findAgeGroupsBySchemeId(schemeId).map(AgeGroupResponse::from);
    }

    @PostMapping("/age-groups")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create an age group")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Age group created"),
        @ApiResponse(responseCode = "400", description = "Validation error")
    })
    public Mono<AgeGroupResponse> createAgeGroup(@Valid @RequestBody CreateAgeGroupRequest request,
                                                 Principal principal) {
        return schemeService.createAgeGroup(request, principal.getName()).map(AgeGroupResponse::from);
    }
}
