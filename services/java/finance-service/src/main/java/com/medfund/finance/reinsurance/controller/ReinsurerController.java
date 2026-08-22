package com.medfund.finance.reinsurance.controller;

import com.medfund.finance.dto.PageResponse;
import com.medfund.finance.reinsurance.dto.CreateReinsurerRequest;
import com.medfund.finance.reinsurance.dto.ReinsurerResponse;
import com.medfund.finance.reinsurance.dto.UpdateReinsurerRequest;
import com.medfund.finance.reinsurance.service.ReinsurerService;
import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.security.Permissions;
import com.medfund.shared.security.RequiresPermission;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/reinsurance/reinsurers")
@RequiredArgsConstructor
@Tag(name = "Reinsurance — Reinsurers",
     description = "Reinsurance counterparty master. A reinsurer is an external counterparty on one or more treaties.")
@SecurityRequirement(name = "bearer-jwt")
public class ReinsurerController {

    private final ReinsurerService service;

    @GetMapping
    @RequiresPermission(Permissions.REINSURANCE_VIEW)
    @Operation(summary = "List reinsurers (paged)",
            description = "Optional active filter. Default page=0 size=50, ordered by name.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Paged reinsurer envelope")})
    public Mono<PageResponse<ReinsurerResponse>> list(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false)    Boolean active) {
        return service.list(page, size, active);
    }

    @GetMapping("/{id}")
    @RequiresPermission(Permissions.REINSURANCE_VIEW)
    @Operation(summary = "Get a reinsurer by id")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Reinsurer returned"),
            @ApiResponse(responseCode = "400", description = "Not found (surfaced as bad-request per IllegalArgumentException handling)")
    })
    public Mono<ReinsurerResponse> get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permissions.REINSURANCE_MANAGE_TREATY)
    @Operation(summary = "Create a reinsurer",
            description = "Emits an audit event under entityType=Reinsurer, entityName=<reinsurer name>.")
    public Mono<ReinsurerResponse> create(@Valid @RequestBody CreateReinsurerRequest body,
                                          @AuthenticationPrincipal Jwt jwt) {
        return service.create(body, AuditActor.id(jwt), AuditActor.email(jwt));
    }

    @PutMapping("/{id}")
    @RequiresPermission(Permissions.REINSURANCE_MANAGE_TREATY)
    @Operation(summary = "Update a reinsurer",
            description = "Full replace. Flip active=false to deactivate — the partial unique index on name will "
                        + "then allow a fresh row with the same display name.")
    public Mono<ReinsurerResponse> update(@PathVariable UUID id,
                                          @Valid @RequestBody UpdateReinsurerRequest body,
                                          @AuthenticationPrincipal Jwt jwt) {
        return service.update(id, body, AuditActor.id(jwt), AuditActor.email(jwt));
    }
}
