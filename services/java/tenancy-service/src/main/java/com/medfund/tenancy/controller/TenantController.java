package com.medfund.tenancy.controller;

import com.medfund.tenancy.dto.CreateTenantRequest;
import com.medfund.tenancy.dto.TenantEmailTemplateResponse;
import com.medfund.tenancy.dto.TenantPage;
import com.medfund.tenancy.dto.TenantQueryParams;
import com.medfund.tenancy.dto.TenantResponse;
import com.medfund.tenancy.dto.UpdateTenantEmailTemplateRequest;
import com.medfund.tenancy.dto.UpdateTenantRequest;
import com.medfund.tenancy.service.TenantEmailTemplateService;
import com.medfund.tenancy.service.TenantService;
import org.springframework.http.ResponseEntity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tenants")
@RequiredArgsConstructor
@Tag(name = "Tenants", description = "Tenant lifecycle management — super admin only")
@SecurityRequirement(name = "bearer-jwt")
public class TenantController {

    private final TenantService tenantService;
    private final TenantEmailTemplateService emailTemplateService;

    @GetMapping
    @Operation(
            summary = "Search tenants",
            description = "Page-based tenant search with optional text search and filters.")
    @ApiResponse(responseCode = "200", description = "Paginated tenant list")
    public Mono<TenantPage> search(
            @Parameter(description = "Full-text search on name, slug, contact email") @RequestParam(required = false) String q,
            @Parameter(description = "Filter by status: active | suspended") @RequestParam(required = false) String status,
            @Parameter(description = "Filter by membership model: INDIVIDUAL_ONLY | GROUP_ONLY | BOTH") @RequestParam(required = false) String membershipModel,
            @Parameter(description = "Filter by 2-letter country code") @RequestParam(required = false) String countryCode,
            @Parameter(description = "Page number, 1-indexed (default 1)") @RequestParam(required = false) Integer page,
            @Parameter(description = "Page size (default 20, max 100)") @RequestParam(required = false) Integer size) {
        return tenantService.search(TenantQueryParams.of(q, status, membershipModel, countryCode, page, size));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get tenant by ID")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Tenant found"),
            @ApiResponse(responseCode = "404", description = "Tenant not found",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public Mono<TenantResponse> findById(@Parameter(description = "Tenant UUID") @PathVariable UUID id) {
        return tenantService.findById(id).map(TenantResponse::from);
    }

    @GetMapping("/slug/{slug}")
    @Operation(summary = "Get tenant by slug")
    @ApiResponse(responseCode = "200", description = "Tenant found")
    public Mono<TenantResponse> findBySlug(@PathVariable String slug) {
        return tenantService.findBySlug(slug).map(TenantResponse::from);
    }

    @GetMapping("/status/{status}")
    @Operation(summary = "List tenants by status")
    @ApiResponse(responseCode = "200", description = "Filtered list of tenants")
    public Flux<TenantResponse> findByStatus(@PathVariable String status) {
        return tenantService.findByStatus(status).map(TenantResponse::from);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a new tenant",
            description = "Provisions a new tenant: creates DB schema, runs Flyway migrations, " +
                    "creates Keycloak realm, seeds default data, and publishes TENANT_PROVISIONED Kafka event.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Tenant created and provisioned"),
            @ApiResponse(responseCode = "400", description = "Validation error",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "Slug already exists",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public Mono<TenantResponse> create(@Valid @RequestBody CreateTenantRequest request,
                                       @AuthenticationPrincipal Jwt jwt) {
        String actorId    = jwt != null ? jwt.getSubject() : "system";
        String actorEmail = jwt != null ? resolveActorEmail(jwt) : null;
        return tenantService.create(request, actorId, actorEmail).map(TenantResponse::from);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update tenant", description = "Update tenant details. Only provided fields are updated.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Tenant updated"),
            @ApiResponse(responseCode = "404", description = "Tenant not found")
    })
    public Mono<TenantResponse> update(@PathVariable UUID id,
                                       @Valid @RequestBody UpdateTenantRequest request,
                                       @AuthenticationPrincipal Jwt jwt) {
        String actorId    = jwt != null ? jwt.getSubject() : "system";
        String actorEmail = jwt != null ? resolveActorEmail(jwt) : null;
        return tenantService.update(id, request, actorId, actorEmail).map(TenantResponse::from);
    }

    @PostMapping("/{id}/suspend")
    @Operation(summary = "Suspend tenant", description = "Suspends a tenant. Data is preserved.")
    @ApiResponse(responseCode = "200", description = "Tenant suspended")
    public Mono<TenantResponse> suspend(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        String actorId    = jwt != null ? jwt.getSubject() : "system";
        String actorEmail = jwt != null ? resolveActorEmail(jwt) : null;
        return tenantService.suspend(id, actorId, actorEmail).map(TenantResponse::from);
    }

    @PostMapping("/{id}/activate")
    @Operation(summary = "Activate tenant", description = "Re-activates a suspended tenant.")
    @ApiResponse(responseCode = "200", description = "Tenant activated")
    public Mono<TenantResponse> activate(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        String actorId    = jwt != null ? jwt.getSubject() : "system";
        String actorEmail = jwt != null ? resolveActorEmail(jwt) : null;
        return tenantService.activate(id, actorId, actorEmail).map(TenantResponse::from);
    }

    // ── Email templates (per-tenant overrides) ────────────────────────────────

    @GetMapping("/{id}/email-templates")
    @Operation(summary = "List email templates for a tenant",
            description = "Returns every template in the platform catalogue. " +
                    "Each entry carries the platform default subject/body plus " +
                    "the tenant's override (if any).")
    @ApiResponse(responseCode = "200", description = "Templates returned")
    public Flux<TenantEmailTemplateResponse> listEmailTemplates(@PathVariable UUID id) {
        return emailTemplateService.listForTenant(id);
    }

    @PutMapping("/{id}/email-templates/{key}")
    @Operation(summary = "Override an email template for a tenant",
            description = "Creates or updates the tenant's override for the given template key. " +
                    "When the override is enabled the next email of this type will use it; " +
                    "when disabled or missing, the platform default is used.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Override saved"),
            @ApiResponse(responseCode = "400", description = "Unknown template key or invalid body",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public Mono<TenantEmailTemplateResponse> upsertEmailTemplate(
            @PathVariable UUID id,
            @PathVariable String key,
            @Valid @RequestBody UpdateTenantEmailTemplateRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        String actorId    = jwt != null ? jwt.getSubject() : "system";
        String actorEmail = jwt != null ? resolveActorEmail(jwt) : null;
        return emailTemplateService.upsert(id, key, request, actorId, actorEmail);
    }

    @DeleteMapping("/{id}/email-templates/{key}")
    @Operation(summary = "Reset a tenant's email template override",
            description = "Removes the tenant's override so subsequent sends fall back " +
                    "to the platform default. Idempotent — succeeds whether or not an " +
                    "override exists.")
    @ApiResponse(responseCode = "204", description = "Override removed (or absent)")
    public Mono<ResponseEntity<Void>> resetEmailTemplate(
            @PathVariable UUID id,
            @PathVariable String key,
            @AuthenticationPrincipal Jwt jwt) {
        String actorId    = jwt != null ? jwt.getSubject() : "system";
        String actorEmail = jwt != null ? resolveActorEmail(jwt) : null;
        return emailTemplateService.reset(id, key, actorId, actorEmail)
                .thenReturn(ResponseEntity.noContent().<Void>build());
    }

    /** Keycloak JWTs carry email in the {@code email} claim; {@code preferred_username} is the fallback. */
    private static String resolveActorEmail(Jwt jwt) {
        String email = jwt.getClaimAsString("email");
        return email != null ? email : jwt.getClaimAsString("preferred_username");
    }
}
