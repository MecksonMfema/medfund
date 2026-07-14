package com.medfund.user.controller;

import com.medfund.shared.audit.AuditActor;
import com.medfund.user.dto.CreatePropertyRequest;
import com.medfund.user.dto.PageResponse;
import com.medfund.user.dto.PropertyFilterParams;
import com.medfund.user.dto.PropertyResponse;
import com.medfund.user.dto.PropertyRow;
import com.medfund.user.dto.UpdatePropertyRequest;
import com.medfund.user.service.PropertyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
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
@RequestMapping("/api/v1/properties")
@Tag(name = "Properties", description = "Property-insurance asset records — property lifecycle, billing-override management")
@SecurityRequirement(name = "bearer-jwt")
public class PropertyController {

    private final PropertyService propertyService;

    public PropertyController(PropertyService propertyService) {
        this.propertyService = propertyService;
    }

    @GetMapping
    @Operation(summary = "List properties (unpaginated — prefer /page)")
    public Flux<PropertyResponse> findAll() {
        return propertyService.findAll().map(PropertyResponse::from);
    }

    @GetMapping("/page")
    @Operation(summary = "List properties (server-side paginated)",
            description = "Rows carry pre-joined scheme + owner-member names. Supports "
                    + "status + schemeId filters, free-text search across property name, "
                    + "address, scheme name, and owner name.")
    public Mono<PageResponse<PropertyRow>> page(@RequestParam(required = false) String status,
                                                 @RequestParam(required = false) UUID schemeId,
                                                 @RequestParam(required = false) String q,
                                                 @RequestParam(required = false) String sortKey,
                                                 @RequestParam(required = false) String sortDirection,
                                                 @RequestParam(defaultValue = "0") int page,
                                                 @RequestParam(defaultValue = "50") int size) {
        return propertyService.searchPaged(new PropertyFilterParams(
                status, schemeId, q, sortKey, sortDirection, page, size));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get property by ID")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Property found"),
            @ApiResponse(responseCode = "404", description = "Property not found")
    })
    public Mono<PropertyResponse> findById(@PathVariable UUID id) {
        return propertyService.findById(id).map(PropertyResponse::from);
    }

    @GetMapping("/scheme/{schemeId}")
    @Operation(summary = "List properties by scheme")
    public Flux<PropertyResponse> findBySchemeId(@PathVariable UUID schemeId) {
        return propertyService.findBySchemeId(schemeId).map(PropertyResponse::from);
    }

    @GetMapping("/owner/{ownerMemberId}")
    @Operation(summary = "List properties by owner member")
    public Flux<PropertyResponse> findByOwnerMemberId(@PathVariable UUID ownerMemberId) {
        return propertyService.findByOwnerMemberId(ownerMemberId).map(PropertyResponse::from);
    }

    @GetMapping("/search")
    @Operation(summary = "Search properties by name or address")
    public Flux<PropertyResponse> search(@RequestParam String q) {
        return propertyService.search(q).map(PropertyResponse::from);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Register a new property")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Property created"),
            @ApiResponse(responseCode = "400", description = "Validation error")
    })
    public Mono<PropertyResponse> create(@Valid @RequestBody CreatePropertyRequest request,
                                         @AuthenticationPrincipal Jwt jwt) {
        return propertyService.create(request, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(PropertyResponse::from);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update property details")
    public Mono<PropertyResponse> update(@PathVariable UUID id,
                                         @Valid @RequestBody UpdatePropertyRequest request,
                                         @AuthenticationPrincipal Jwt jwt) {
        return propertyService.update(id, request, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(PropertyResponse::from);
    }

    @PostMapping("/{id}/suspend")
    @Operation(summary = "Suspend property")
    public Mono<PropertyResponse> suspend(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return propertyService.suspend(id, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(PropertyResponse::from);
    }

    @PostMapping("/{id}/terminate")
    @Operation(summary = "Terminate property policy")
    public Mono<PropertyResponse> terminate(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return propertyService.terminate(id, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(PropertyResponse::from);
    }

    @PostMapping("/{id}/clear-billing-override")
    @Operation(summary = "Clear the property's per-asset pricing override",
            description = "Nulls billing_override_amount, reason, and effective_from so billing falls back to scheme defaults.")
    public Mono<PropertyResponse> clearBillingOverride(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return propertyService.clearBillingOverride(id, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(PropertyResponse::from);
    }
}
