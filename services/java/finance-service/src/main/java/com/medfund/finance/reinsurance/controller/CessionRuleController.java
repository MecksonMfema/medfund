package com.medfund.finance.reinsurance.controller;

import com.medfund.finance.reinsurance.dto.CessionRuleResponse;
import com.medfund.finance.reinsurance.dto.CreateCessionRuleRequest;
import com.medfund.finance.reinsurance.dto.UpdateCessionRuleRequest;
import com.medfund.finance.reinsurance.service.CessionRuleService;
import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.security.Permissions;
import com.medfund.shared.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/reinsurance/treaties/{treatyId}/cession-rules")
@RequiredArgsConstructor
@Tag(name = "Reinsurance — Cession Rules",
     description = "Links between a treaty and rules-engine RuleDefinitions. The rule authoring lives in the "
                 + "standard visual rule builder with category=REINSURANCE.")
@SecurityRequirement(name = "bearer-jwt")
public class CessionRuleController {

    private final CessionRuleService service;

    @GetMapping
    @RequiresPermission(Permissions.REINSURANCE_VIEW)
    @Operation(summary = "List cession rule links for a treaty")
    public Flux<CessionRuleResponse> list(@PathVariable UUID treatyId) {
        return service.list(treatyId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permissions.REINSURANCE_MANAGE_TREATY)
    @Operation(summary = "Link a rule to a DRAFT treaty")
    public Mono<CessionRuleResponse> add(@PathVariable UUID treatyId,
                                         @Valid @RequestBody CreateCessionRuleRequest body,
                                         @AuthenticationPrincipal Jwt jwt) {
        return service.add(treatyId, body, AuditActor.id(jwt), AuditActor.email(jwt));
    }

    @PutMapping("/{linkId}")
    @RequiresPermission(Permissions.REINSURANCE_MANAGE_TREATY)
    @Operation(summary = "Toggle enabled on a cession rule link",
            description = "Enabled can be flipped on ACTIVE treaties too — useful for pausing a rule without "
                        + "revoking the treaty. Only add/remove requires DRAFT.")
    public Mono<CessionRuleResponse> update(@PathVariable UUID treatyId,
                                            @PathVariable UUID linkId,
                                            @Valid @RequestBody UpdateCessionRuleRequest body,
                                            @AuthenticationPrincipal Jwt jwt) {
        return service.updateEnabled(treatyId, linkId, body, AuditActor.id(jwt), AuditActor.email(jwt));
    }

    @DeleteMapping("/{linkId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(Permissions.REINSURANCE_MANAGE_TREATY)
    @Operation(summary = "Unlink a rule from a DRAFT treaty")
    public Mono<Void> delete(@PathVariable UUID treatyId,
                             @PathVariable UUID linkId,
                             @AuthenticationPrincipal Jwt jwt) {
        return service.delete(treatyId, linkId, AuditActor.id(jwt), AuditActor.email(jwt));
    }
}
