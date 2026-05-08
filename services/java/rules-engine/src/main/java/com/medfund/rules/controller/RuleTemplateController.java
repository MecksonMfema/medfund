package com.medfund.rules.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.rules.dto.RuleTemplateResponse;
import com.medfund.rules.model.RuleDefinition;
import com.medfund.rules.service.RuleTemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Read-only browser of the rule templates seeded by {@link RuleTemplateService}.
 * Tenant admins use these as starting points in the New Rule modal.
 *
 * <p>This endpoint is platform-wide (templates are the same for every tenant)
 * but is still served behind authentication. The path is in the gateway's
 * proxy table but NOT in {@code TenantWebFilter.PLATFORM_PATHS}, so a tenant
 * header is required — that's deliberate: only authenticated tenant users
 * should browse templates.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/rule-templates")
@RequiredArgsConstructor
@Tag(name = "Rule Templates", description = "Read-only catalogue of seeded rule starting points.")
@SecurityRequirement(name = "bearer-jwt")
public class RuleTemplateController {

    private final RuleTemplateService templateService;
    private final ObjectMapper        objectMapper;

    @GetMapping
    @Operation(summary = "List all seeded rule templates")
    public Flux<RuleTemplateResponse> list() {
        return Flux.fromIterable(templateService.getDefaultRules()).map(this::toResponse);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one template by id")
    @ApiResponse(responseCode = "200", description = "Template found")
    @ApiResponse(responseCode = "404", description = "Template not found")
    public Mono<RuleTemplateResponse> get(@PathVariable String id) {
        return Flux.fromIterable(templateService.getDefaultRules())
                .filter(r -> id.equals(r.getId()) || id.equals(r.getName()))
                .next()
                .map(this::toResponse)
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Template not found: " + id)));
    }

    private RuleTemplateResponse toResponse(RuleDefinition def) {
        try {
            return new RuleTemplateResponse(
                    def.getId(),
                    def.getName(),
                    def.getDescription(),
                    def.getCategory(),
                    def.getPriority(),
                    objectMapper.valueToTree(def)
            );
        } catch (Exception e) {
            log.warn("Could not serialize template {}: {}", def.getName(), e.getMessage());
            return new RuleTemplateResponse(def.getId(), def.getName(), def.getDescription(),
                    def.getCategory(), def.getPriority(), objectMapper.createObjectNode());
        }
    }
}
