package com.medfund.rules.controller;

import com.medfund.rules.dto.CreateRuleRequest;
import com.medfund.rules.dto.DryRunRequest;
import com.medfund.rules.dto.DryRunResponse;
import com.medfund.rules.dto.TenantRuleResponse;
import com.medfund.rules.dto.UpdateRuleRequest;
import com.medfund.rules.entity.TenantRule;
import com.medfund.rules.service.TenantRuleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Tenant rule CRUD. Every endpoint requires {@code X-Tenant-ID}; the
 * shared {@code TenantWebFilter} rejects anything else with 400 + ProblemDetail
 * before the request reaches a handler here.
 */
@RestController
@RequestMapping("/api/v1/rules")
@RequiredArgsConstructor
@Tag(name = "Tenant Rules", description = "Per-tenant Drools rules — list, edit, dry-run.")
@SecurityRequirement(name = "bearer-jwt")
public class TenantRuleController {

    private final TenantRuleService service;

    @GetMapping
    @Operation(summary = "List rules for the current tenant",
               description = "Optional ?category= filters to one of ELIGIBILITY / WAITING_PERIOD / "
                           + "BENEFIT_LIMIT / PRE_AUTHORIZATION / TARIFF_PRICING / CLINICAL_VALIDATION.")
    public Flux<TenantRuleResponse> list(
            @RequestHeader("X-Tenant-ID") UUID tenantId,
            @RequestParam(required = false) String category) {
        return service.findByTenant(tenantId, category).map(this::toResponse);
    }

    @GetMapping("/{id:[0-9a-fA-F-]+}")
    @Operation(summary = "Get a single rule")
    @ApiResponse(responseCode = "200", description = "Rule found")
    @ApiResponse(responseCode = "404", description = "Rule not found")
    public Mono<TenantRuleResponse> get(
            @RequestHeader("X-Tenant-ID") UUID tenantId,
            @PathVariable UUID id) {
        return service.findById(id, tenantId).map(this::toResponse);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a rule",
               description = "Hot-reloads the tenant's KieContainer once persisted.")
    @ApiResponse(responseCode = "201", description = "Rule created")
    @ApiResponse(responseCode = "409", description = "rule_key already in use for this tenant")
    public Mono<TenantRuleResponse> create(
            @RequestHeader("X-Tenant-ID") UUID tenantId,
            @Valid @RequestBody CreateRuleRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        UUID actorId = actorId(jwt);
        return service.create(tenantId, request, actorId).map(this::toResponse);
    }

    @PutMapping("/{id:[0-9a-fA-F-]+}")
    @Operation(summary = "Update a rule",
               description = "Partial update — null fields are left untouched. Bumps version.")
    public Mono<TenantRuleResponse> update(
            @RequestHeader("X-Tenant-ID") UUID tenantId,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateRuleRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        UUID actorId = actorId(jwt);
        return service.update(id, tenantId, request, actorId).map(this::toResponse);
    }

    @DeleteMapping("/{id:[0-9a-fA-F-]+}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a rule")
    public Mono<Void> delete(
            @RequestHeader("X-Tenant-ID") UUID tenantId,
            @PathVariable UUID id) {
        return service.delete(id, tenantId);
    }

    @PostMapping("/{id:[0-9a-fA-F-]+}/enable")
    @Operation(summary = "Enable a rule")
    public Mono<TenantRuleResponse> enable(
            @RequestHeader("X-Tenant-ID") UUID tenantId,
            @PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt) {
        return service.setEnabled(id, tenantId, true, actorId(jwt)).map(this::toResponse);
    }

    @PostMapping("/{id:[0-9a-fA-F-]+}/disable")
    @Operation(summary = "Disable a rule")
    public Mono<TenantRuleResponse> disable(
            @RequestHeader("X-Tenant-ID") UUID tenantId,
            @PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt) {
        return service.setEnabled(id, tenantId, false, actorId(jwt)).map(this::toResponse);
    }

    @PostMapping("/{id:[0-9a-fA-F-]+}/dry-run")
    @Operation(summary = "Dry-run a rule against sample facts",
               description = "Loads only this rule into a temporary KieSession, evaluates the "
                           + "supplied facts, and returns the decision and any rule results.")
    public Mono<DryRunResponse> dryRun(
            @RequestHeader("X-Tenant-ID") UUID tenantId,
            @PathVariable UUID id,
            @Valid @RequestBody DryRunRequest request) {
        return service.dryRun(id, tenantId, request);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private TenantRuleResponse toResponse(TenantRule rule) {
        return new TenantRuleResponse(
                rule.getId(),
                rule.getTenantId(),
                rule.getRuleKey(),
                rule.getName(),
                rule.getDescription(),
                rule.getCategory(),
                rule.getTemplateId(),
                rule.getPriority(),
                service.parseDefinition(rule.getDefinition()),
                rule.getDrlOverride(),
                rule.getEnabled(),
                rule.getVersion(),
                rule.getCreatedAt(),
                rule.getUpdatedAt()
        );
    }

    private static UUID actorId(Jwt jwt) {
        if (jwt == null) return null;
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
