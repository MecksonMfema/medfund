package com.medfund.tenancy.controller;

import com.medfund.tenancy.dto.MemberNumberConfigResponse;
import com.medfund.tenancy.dto.UpdateMemberNumberConfigRequest;
import com.medfund.tenancy.service.MemberNumberConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/member-number-config")
@RequiredArgsConstructor
@Tag(name = "Tenant member-number config",
     description = "V126 shape knobs for member/dependant number issuance — prefix, random length, suffix separator/padding/start.")
@SecurityRequirement(name = "bearer-jwt")
public class MemberNumberConfigController {

    private final MemberNumberConfigService service;

    @GetMapping
    @Operation(summary = "Fetch the tenant's current member-number config")
    public Mono<MemberNumberConfigResponse> get(@PathVariable UUID tenantId) {
        return service.get(tenantId);
    }

    @PutMapping
    @Operation(summary = "Update the tenant's member-number config",
            description = "All fields required — validation mirrors the V126 CHECK constraints (random_length ∈ [3,12], padding ∈ [1,4]).")
    public Mono<MemberNumberConfigResponse> update(@PathVariable UUID tenantId,
                                                    @Valid @RequestBody UpdateMemberNumberConfigRequest body) {
        return service.update(tenantId, body);
    }
}
