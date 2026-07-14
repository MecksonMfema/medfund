package com.medfund.user.controller;

import com.medfund.shared.audit.AuditActor;
import com.medfund.user.dto.CreateVehicleRequest;
import com.medfund.user.dto.PageResponse;
import com.medfund.user.dto.UpdateVehicleRequest;
import com.medfund.user.dto.VehicleFilterParams;
import com.medfund.user.dto.VehicleResponse;
import com.medfund.user.dto.VehicleRow;
import com.medfund.user.service.VehicleService;
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
@RequestMapping("/api/v1/vehicles")
@Tag(name = "Vehicles", description = "Motor-insurance asset records — vehicle lifecycle, billing-override management")
@SecurityRequirement(name = "bearer-jwt")
public class VehicleController {

    private final VehicleService vehicleService;

    public VehicleController(VehicleService vehicleService) {
        this.vehicleService = vehicleService;
    }

    @GetMapping
    @Operation(summary = "List vehicles (unpaginated — prefer /page)")
    public Flux<VehicleResponse> findAll() {
        return vehicleService.findAll().map(VehicleResponse::from);
    }

    @GetMapping("/page")
    @Operation(summary = "List vehicles (server-side paginated)",
            description = "Returns rows with pre-joined scheme + owner-member names so the "
                    + "operational table never renders raw UUIDs. Supports status + schemeId "
                    + "filters, free-text search across registration, make, model, scheme "
                    + "name, and owner name, plus sortKey/sortDirection.")
    public Mono<PageResponse<VehicleRow>> page(@RequestParam(required = false) String status,
                                                @RequestParam(required = false) UUID schemeId,
                                                @RequestParam(required = false) String q,
                                                @RequestParam(required = false) String sortKey,
                                                @RequestParam(required = false) String sortDirection,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "50") int size) {
        return vehicleService.searchPaged(new VehicleFilterParams(
                status, schemeId, q, sortKey, sortDirection, page, size));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get vehicle by ID")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Vehicle found"),
            @ApiResponse(responseCode = "404", description = "Vehicle not found")
    })
    public Mono<VehicleResponse> findById(@PathVariable UUID id) {
        return vehicleService.findById(id).map(VehicleResponse::from);
    }

    @GetMapping("/scheme/{schemeId}")
    @Operation(summary = "List vehicles by scheme")
    public Flux<VehicleResponse> findBySchemeId(@PathVariable UUID schemeId) {
        return vehicleService.findBySchemeId(schemeId).map(VehicleResponse::from);
    }

    @GetMapping("/owner/{ownerMemberId}")
    @Operation(summary = "List vehicles by owner member")
    public Flux<VehicleResponse> findByOwnerMemberId(@PathVariable UUID ownerMemberId) {
        return vehicleService.findByOwnerMemberId(ownerMemberId).map(VehicleResponse::from);
    }

    @GetMapping("/search")
    @Operation(summary = "Search vehicles by registration, make, or model")
    public Flux<VehicleResponse> search(@RequestParam String q) {
        return vehicleService.search(q).map(VehicleResponse::from);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Register a new vehicle")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Vehicle created"),
            @ApiResponse(responseCode = "400", description = "Validation error")
    })
    public Mono<VehicleResponse> create(@Valid @RequestBody CreateVehicleRequest request,
                                         @AuthenticationPrincipal Jwt jwt) {
        return vehicleService.create(request, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(VehicleResponse::from);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update vehicle details")
    public Mono<VehicleResponse> update(@PathVariable UUID id,
                                         @Valid @RequestBody UpdateVehicleRequest request,
                                         @AuthenticationPrincipal Jwt jwt) {
        return vehicleService.update(id, request, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(VehicleResponse::from);
    }

    @PostMapping("/{id}/suspend")
    @Operation(summary = "Suspend vehicle")
    public Mono<VehicleResponse> suspend(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return vehicleService.suspend(id, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(VehicleResponse::from);
    }

    @PostMapping("/{id}/terminate")
    @Operation(summary = "Terminate vehicle policy")
    public Mono<VehicleResponse> terminate(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return vehicleService.terminate(id, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(VehicleResponse::from);
    }

    @PostMapping("/{id}/clear-billing-override")
    @Operation(summary = "Clear the vehicle's per-asset pricing override",
            description = "Nulls billing_override_amount, reason, and effective_from so billing falls back to scheme defaults.")
    public Mono<VehicleResponse> clearBillingOverride(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return vehicleService.clearBillingOverride(id, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(VehicleResponse::from);
    }
}
