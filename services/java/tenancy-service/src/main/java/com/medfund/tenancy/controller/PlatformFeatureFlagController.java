package com.medfund.tenancy.controller;

import com.medfund.shared.audit.AuditActor;
import com.medfund.tenancy.dto.PlatformFeatureFlagResponse;
import com.medfund.tenancy.dto.UpdateFeatureFlagRequest;
import com.medfund.tenancy.service.PlatformFeatureFlagService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/platform/feature-flags")
@RequiredArgsConstructor
@Tag(name = "Platform Feature Flags", description = "Platform-wide feature toggles")
@SecurityRequirement(name = "bearer-jwt")
public class PlatformFeatureFlagController {

    private final PlatformFeatureFlagService svc;

    @GetMapping
    @Operation(summary = "List all platform feature flags with their current enabled state")
    public Flux<PlatformFeatureFlagResponse> list() {
        return svc.list();
    }

    @GetMapping("/{key}")
    @Operation(summary = "Fetch one flag by key — used by Elixir/Go services that can't hit R2DBC directly")
    public Mono<PlatformFeatureFlagResponse> get(@PathVariable String key) {
        return svc.get(key);
    }

    @PutMapping("/{key}")
    @Operation(summary = "Toggle a feature flag on or off")
    public Mono<PlatformFeatureFlagResponse> update(@PathVariable String key,
                                                    @Valid @RequestBody UpdateFeatureFlagRequest req,
                                                    @AuthenticationPrincipal Jwt jwt) {
        return svc.update(key, req.enabled(), AuditActor.id(jwt), AuditActor.email(jwt));
    }
}
