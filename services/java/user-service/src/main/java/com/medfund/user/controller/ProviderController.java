package com.medfund.user.controller;

import com.medfund.shared.audit.AuditActor;
import com.medfund.user.dto.CreateProviderRequest;
import com.medfund.user.dto.ProviderPage;
import com.medfund.user.dto.ProviderResponse;
import com.medfund.user.dto.UpdateProviderRequest;
import com.medfund.user.service.ProviderService;
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
@RequestMapping("/api/v1/providers")
@RequiredArgsConstructor
@Tag(name = "Providers", description = "Provider onboarding and management — platform-wide registry")
@SecurityRequirement(name = "bearer-jwt")
public class ProviderController {

    private final ProviderService providerService;

    @GetMapping
    @Operation(summary = "Search and list providers (paginated)",
        description = "All params are optional. Returns a page object with content, totalCount, totalPages.")
    public Mono<ProviderPage> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String providerType,
            @RequestParam(defaultValue = "1")  int page,
            @RequestParam(defaultValue = "20") int size) {
        return providerService.searchPage(
            (q != null && !q.isBlank()) ? q : null,
            (status != null && !status.isBlank()) ? status : null,
            (providerType != null && !providerType.isBlank()) ? providerType : null,
            page, size);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get provider by ID")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Provider found"),
        @ApiResponse(responseCode = "404", description = "Provider not found")
    })
    public Mono<ProviderResponse> findById(@PathVariable UUID id) {
        return providerService.findById(id).map(ProviderResponse::from);
    }

    @GetMapping("/status/{status}")
    @Operation(summary = "List providers by status")
    public Flux<ProviderResponse> findByStatus(@PathVariable String status) {
        return providerService.findByStatus(status).map(ProviderResponse::from);
    }

    @GetMapping("/specialty/{specialty}")
    @Operation(summary = "List providers by specialty")
    public Flux<ProviderResponse> findBySpecialty(@PathVariable String specialty) {
        return providerService.findBySpecialty(specialty).map(ProviderResponse::from);
    }

    @GetMapping("/search")
    @Operation(summary = "Search providers by name or practice number")
    public Flux<ProviderResponse> search(@RequestParam String q) {
        return providerService.search(q).map(ProviderResponse::from);
    }

    @GetMapping("/registration/{registrationNumber}")
    @Operation(summary = "Find provider by registration number")
    public Mono<ProviderResponse> findByRegistrationNumber(@PathVariable String registrationNumber) {
        return providerService.findByRegistrationNumber(registrationNumber).map(ProviderResponse::from);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Onboard a new provider",
        description = "Creates provider with pending_verification status, syncs to Keycloak, publishes onboarding event")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Provider onboarded"),
        @ApiResponse(responseCode = "400", description = "Validation error")
    })
    public Mono<ProviderResponse> onboard(@Valid @RequestBody CreateProviderRequest request,
                                           @AuthenticationPrincipal Jwt jwt) {
        return providerService.onboard(request, AuditActor.id(jwt), AuditActor.email(jwt))
                              .map(ProviderResponse::from);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update provider details")
    public Mono<ProviderResponse> update(@PathVariable UUID id,
                                          @Valid @RequestBody UpdateProviderRequest request,
                                          @AuthenticationPrincipal Jwt jwt) {
        return providerService.update(id, request, AuditActor.id(jwt), AuditActor.email(jwt))
                              .map(ProviderResponse::from);
    }

    @PostMapping("/{id}/verify")
    @Operation(summary = "Verify and activate provider")
    public Mono<ProviderResponse> verifyAhfoz(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return providerService.verifyAhfoz(id, AuditActor.id(jwt), AuditActor.email(jwt))
                              .map(ProviderResponse::from);
    }

    @PostMapping("/{id}/suspend")
    @Operation(summary = "Suspend provider")
    public Mono<ProviderResponse> suspend(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return providerService.suspend(id, AuditActor.id(jwt), AuditActor.email(jwt))
                              .map(ProviderResponse::from);
    }

    @PostMapping("/{id}/activate")
    @Operation(summary = "Re-activate a suspended provider")
    public Mono<ProviderResponse> activate(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return providerService.activate(id, AuditActor.id(jwt), AuditActor.email(jwt))
                              .map(ProviderResponse::from);
    }

}
