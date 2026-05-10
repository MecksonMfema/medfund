package com.medfund.finance.controller;

import com.medfund.finance.dto.AdjustmentResponse;
import com.medfund.finance.dto.CreateAdjustmentRequest;
import com.medfund.finance.service.AdjustmentService;
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
@RequestMapping("/api/v1/adjustments")
@Tag(name = "Adjustments", description = "Financial adjustments — creation, approval, and application")
@SecurityRequirement(name = "bearer-jwt")
public class AdjustmentController {

    private final AdjustmentService adjustmentService;

    public AdjustmentController(AdjustmentService adjustmentService) {
        this.adjustmentService = adjustmentService;
    }

    @GetMapping("/provider/{providerId}")
    @Operation(summary = "List adjustments by provider")
    public Flux<AdjustmentResponse> findByProviderId(@PathVariable UUID providerId) {
        return adjustmentService.findByProviderId(providerId).map(AdjustmentResponse::from);
    }

    @GetMapping("/status/{status}")
    @Operation(summary = "List adjustments by status")
    public Flux<AdjustmentResponse> findByStatus(@PathVariable String status) {
        return adjustmentService.findByStatus(status).map(AdjustmentResponse::from);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get adjustment by ID")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Adjustment found"),
        @ApiResponse(responseCode = "404", description = "Adjustment not found")
    })
    public Mono<AdjustmentResponse> findById(@PathVariable UUID id) {
        return adjustmentService.findById(id).map(AdjustmentResponse::from);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a new adjustment",
        description = "Creates an adjustment record with auto-generated adjustment number")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Adjustment created"),
        @ApiResponse(responseCode = "400", description = "Validation error")
    })
    public Mono<AdjustmentResponse> create(@Valid @RequestBody CreateAdjustmentRequest request, Principal principal) {
        return adjustmentService.create(request, principal.getName()).map(AdjustmentResponse::from);
    }

    @PostMapping("/{id}/approve")
    @Operation(summary = "Approve an adjustment")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Adjustment approved"),
        @ApiResponse(responseCode = "404", description = "Adjustment not found")
    })
    public Mono<AdjustmentResponse> approve(@PathVariable UUID id, Principal principal) {
        return adjustmentService.approve(id, principal.getName()).map(AdjustmentResponse::from);
    }

    @PostMapping("/{id}/apply")
    @Operation(summary = "Apply an approved adjustment")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Adjustment applied"),
        @ApiResponse(responseCode = "404", description = "Adjustment not found")
    })
    public Mono<AdjustmentResponse> apply(@PathVariable UUID id, Principal principal) {
        return adjustmentService.apply(id, principal.getName()).map(AdjustmentResponse::from);
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel a pending or approved adjustment",
        description = "Applied adjustments cannot be cancelled — post a reversing adjustment instead.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Adjustment cancelled"),
        @ApiResponse(responseCode = "400", description = "Cannot cancel an applied adjustment"),
        @ApiResponse(responseCode = "404", description = "Adjustment not found")
    })
    public Mono<AdjustmentResponse> cancel(@PathVariable UUID id, Principal principal) {
        return adjustmentService.cancel(id, principal.getName()).map(AdjustmentResponse::from);
    }
}
