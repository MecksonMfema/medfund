package com.medfund.finance.reinsurance.controller;

import com.medfund.finance.dto.PageResponse;
import com.medfund.finance.reinsurance.dto.CreateTreatyRequest;
import com.medfund.finance.reinsurance.dto.RenewTreatyRequest;
import com.medfund.finance.reinsurance.dto.TreatyResponse;
import com.medfund.finance.reinsurance.dto.UpdateTreatyRequest;
import com.medfund.finance.reinsurance.service.TreatyService;
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

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/reinsurance/treaties")
@RequiredArgsConstructor
@Tag(name = "Reinsurance — Treaties",
     description = "Treaty lifecycle (DRAFT → ACTIVE → terminal). DRAFT is the only editable state; ACTIVE edits "
                 + "return 409 Conflict — correct via void + re-create for pre-inception mistakes, or renew "
                 + "for legitimate successors.")
@SecurityRequirement(name = "bearer-jwt")
public class TreatyController {

    private final TreatyService service;

    @GetMapping
    @RequiresPermission(Permissions.REINSURANCE_VIEW)
    @Operation(summary = "List treaties (paged)",
            description = "Optional status filter. Default page=0 size=50, ordered by inception_date DESC.")
    public Mono<PageResponse<TreatyResponse>> list(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false)    String status) {
        return service.list(page, size, status);
    }

    @GetMapping("/{id}")
    @RequiresPermission(Permissions.REINSURANCE_VIEW)
    @Operation(summary = "Get a treaty by id")
    public Mono<TreatyResponse> get(@PathVariable UUID id) {
        return service.get(id);
    }

    @GetMapping("/{id}/renewal-chain")
    @RequiresPermission(Permissions.REINSURANCE_VIEW)
    @Operation(summary = "Walk the renewal chain root-first",
            description = "Returns every treaty on the chain from the earliest (root) to this treaty. Useful for "
                        + "showing 'renewed from…' timelines on the treaty edit page.")
    public Mono<List<TreatyResponse>> renewalChain(@PathVariable UUID id) {
        return service.renewalChain(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permissions.REINSURANCE_MANAGE_TREATY)
    @Operation(summary = "Create a treaty in DRAFT state",
            description = "Cannot activate at creation — treaty must be enriched with participants, layers, "
                        + "applicable lines, and cession rules first. Use POST /{id}/activate to transition.")
    public Mono<TreatyResponse> create(@Valid @RequestBody CreateTreatyRequest body,
                                       @AuthenticationPrincipal Jwt jwt) {
        return service.createDraft(body, AuditActor.id(jwt), AuditActor.email(jwt));
    }

    @PutMapping("/{id}")
    @RequiresPermission(Permissions.REINSURANCE_MANAGE_TREATY)
    @Operation(summary = "Update a DRAFT treaty",
            description = "Only DRAFT is editable. Any other status returns 409.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Updated"),
            @ApiResponse(responseCode = "409", description = "Treaty is not DRAFT — void + re-create or renew instead")
    })
    public Mono<TreatyResponse> update(@PathVariable UUID id,
                                       @Valid @RequestBody UpdateTreatyRequest body,
                                       @AuthenticationPrincipal Jwt jwt) {
        return service.update(id, body, AuditActor.id(jwt), AuditActor.email(jwt));
    }

    @PostMapping("/{id}/activate")
    @RequiresPermission(Permissions.REINSURANCE_MANAGE_TREATY)
    @Operation(summary = "Transition a DRAFT treaty to ACTIVE",
            description = "Runs the R9 validation: participant shares must sum to 100%, at least one applicable "
                        + "line, and (for XoL/StopLoss) at least one layer. Emits AuditEvent action=ACTIVATE.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Activated"),
            @ApiResponse(responseCode = "400", description = "Validation failed — see message"),
            @ApiResponse(responseCode = "409", description = "Treaty is not DRAFT")
    })
    public Mono<TreatyResponse> activate(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return service.activate(id, AuditActor.id(jwt), AuditActor.email(jwt));
    }

    @PostMapping("/{id}/void")
    @RequiresPermission(Permissions.REINSURANCE_MANAGE_TREATY)
    @Operation(summary = "Void a DRAFT treaty (transitions to LAPSED)",
            description = "Only DRAFT treaties can be voided. ACTIVE treaties must be commuted instead — that "
                        + "surface lives on the reinsurance operator queue, not here.")
    public Mono<TreatyResponse> voidDraft(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return service.voidDraft(id, AuditActor.id(jwt), AuditActor.email(jwt));
    }

    @PostMapping("/{id}/renew")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permissions.REINSURANCE_MANAGE_TREATY)
    @Operation(summary = "Create a successor treaty renewing this one",
            description = "Creates a new DRAFT with renewedFromTreatyId set to this treaty. Copies treaty type, "
                        + "declared currency, and producer ref forward; the caller supplies the new period + "
                        + "financial numbers.")
    public Mono<TreatyResponse> renew(@PathVariable UUID id,
                                      @Valid @RequestBody RenewTreatyRequest body,
                                      @AuthenticationPrincipal Jwt jwt) {
        return service.renew(id, body, AuditActor.id(jwt), AuditActor.email(jwt));
    }
}
