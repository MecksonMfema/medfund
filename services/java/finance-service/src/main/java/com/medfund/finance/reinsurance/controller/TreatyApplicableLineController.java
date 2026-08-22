package com.medfund.finance.reinsurance.controller;

import com.medfund.finance.reinsurance.dto.CreateTreatyApplicableLineRequest;
import com.medfund.finance.reinsurance.dto.TreatyApplicableLineResponse;
import com.medfund.finance.reinsurance.service.TreatyApplicableLineService;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/reinsurance/treaties/{treatyId}/applicable-lines")
@RequiredArgsConstructor
@Tag(name = "Reinsurance — Treaty Applicable Lines",
     description = "The insurance lines a treaty covers. Auto-cession consumers filter treaties on this table.")
@SecurityRequirement(name = "bearer-jwt")
public class TreatyApplicableLineController {

    private final TreatyApplicableLineService service;

    @GetMapping
    @RequiresPermission(Permissions.REINSURANCE_VIEW)
    @Operation(summary = "List applicable lines for a treaty")
    public Flux<TreatyApplicableLineResponse> list(@PathVariable UUID treatyId) {
        return service.list(treatyId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permissions.REINSURANCE_MANAGE_TREATY)
    @Operation(summary = "Add an insurance line to a DRAFT treaty")
    public Mono<TreatyApplicableLineResponse> add(@PathVariable UUID treatyId,
                                                  @Valid @RequestBody CreateTreatyApplicableLineRequest body,
                                                  @AuthenticationPrincipal Jwt jwt) {
        return service.add(treatyId, body, AuditActor.id(jwt), AuditActor.email(jwt));
    }

    @DeleteMapping("/{insuranceLine}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(Permissions.REINSURANCE_MANAGE_TREATY)
    @Operation(summary = "Remove an insurance line from a DRAFT treaty")
    public Mono<Void> remove(@PathVariable UUID treatyId,
                             @PathVariable String insuranceLine,
                             @AuthenticationPrincipal Jwt jwt) {
        return service.remove(treatyId, insuranceLine, AuditActor.id(jwt), AuditActor.email(jwt));
    }
}
