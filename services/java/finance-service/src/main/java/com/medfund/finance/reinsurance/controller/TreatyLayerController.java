package com.medfund.finance.reinsurance.controller;

import com.medfund.finance.reinsurance.dto.TreatyLayerResponse;
import com.medfund.finance.reinsurance.dto.UpsertTreatyLayerRequest;
import com.medfund.finance.reinsurance.service.TreatyLayerService;
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
@RequestMapping("/api/v1/reinsurance/treaties/{treatyId}/layers")
@RequiredArgsConstructor
@Tag(name = "Reinsurance — Treaty Layers",
     description = "Ordered layers of an XoL/StopLoss treaty. Proportional treaties (QS/SS) don't use layers — "
                 + "attempts to add a layer to a QS treaty pass but are ignored by the cession rules.")
@SecurityRequirement(name = "bearer-jwt")
public class TreatyLayerController {

    private final TreatyLayerService service;

    @GetMapping
    @RequiresPermission(Permissions.REINSURANCE_VIEW)
    @Operation(summary = "List treaty layers ordered by layer_order")
    public Flux<TreatyLayerResponse> list(@PathVariable UUID treatyId) {
        return service.list(treatyId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permissions.REINSURANCE_MANAGE_TREATY)
    @Operation(summary = "Add a layer (treaty must be DRAFT)")
    public Mono<TreatyLayerResponse> create(@PathVariable UUID treatyId,
                                            @Valid @RequestBody UpsertTreatyLayerRequest body,
                                            @AuthenticationPrincipal Jwt jwt) {
        return service.create(treatyId, body, AuditActor.id(jwt), AuditActor.email(jwt));
    }

    @PutMapping("/{layerId}")
    @RequiresPermission(Permissions.REINSURANCE_MANAGE_TREATY)
    @Operation(summary = "Update a layer (treaty must be DRAFT)")
    public Mono<TreatyLayerResponse> update(@PathVariable UUID treatyId,
                                            @PathVariable UUID layerId,
                                            @Valid @RequestBody UpsertTreatyLayerRequest body,
                                            @AuthenticationPrincipal Jwt jwt) {
        return service.update(treatyId, layerId, body, AuditActor.id(jwt), AuditActor.email(jwt));
    }

    @DeleteMapping("/{layerId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(Permissions.REINSURANCE_MANAGE_TREATY)
    @Operation(summary = "Remove a layer (treaty must be DRAFT)")
    public Mono<Void> delete(@PathVariable UUID treatyId,
                             @PathVariable UUID layerId,
                             @AuthenticationPrincipal Jwt jwt) {
        return service.delete(treatyId, layerId, AuditActor.id(jwt), AuditActor.email(jwt));
    }
}
