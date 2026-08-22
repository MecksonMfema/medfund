package com.medfund.finance.reinsurance.controller;

import com.medfund.finance.reinsurance.dto.TreatyParticipantResponse;
import com.medfund.finance.reinsurance.dto.UpsertTreatyParticipantRequest;
import com.medfund.finance.reinsurance.service.TreatyParticipantService;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/reinsurance/treaties/{treatyId}/participants")
@RequiredArgsConstructor
@Tag(name = "Reinsurance — Treaty Participants",
     description = "The (treaty, reinsurer) participation table. sharePct across all rows must sum to 100 "
                 + "for treaty activation.")
@SecurityRequirement(name = "bearer-jwt")
public class TreatyParticipantController {

    private final TreatyParticipantService service;

    @GetMapping
    @RequiresPermission(Permissions.REINSURANCE_VIEW)
    @Operation(summary = "List participants for a treaty (leader first)")
    public Flux<TreatyParticipantResponse> list(@PathVariable UUID treatyId) {
        return service.list(treatyId);
    }

    @PutMapping
    @RequiresPermission(Permissions.REINSURANCE_MANAGE_TREATY)
    @Operation(summary = "Upsert a participant by (treatyId, reinsurerId)",
            description = "Idempotent — existing (treatyId, reinsurerId) is updated; new is inserted. Treaty must be DRAFT.")
    public Mono<TreatyParticipantResponse> upsert(@PathVariable UUID treatyId,
                                                  @Valid @RequestBody UpsertTreatyParticipantRequest body,
                                                  @AuthenticationPrincipal Jwt jwt) {
        return service.upsert(treatyId, body, AuditActor.id(jwt), AuditActor.email(jwt));
    }

    @DeleteMapping("/{reinsurerId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(Permissions.REINSURANCE_MANAGE_TREATY)
    @Operation(summary = "Remove a participant from a DRAFT treaty")
    public Mono<Void> delete(@PathVariable UUID treatyId,
                             @PathVariable UUID reinsurerId,
                             @AuthenticationPrincipal Jwt jwt) {
        return service.delete(treatyId, reinsurerId, AuditActor.id(jwt), AuditActor.email(jwt));
    }
}
