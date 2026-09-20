package com.medfund.tenancy.controller;

import com.medfund.tenancy.service.PlatformFeatureFlagService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * Internal read-only flag snapshot for Go services, which have no R2DBC
 * access to {@code public.platform_feature_flags} and therefore cannot use
 * the shared Java {@code FlagRegistry}. Called once at startup to seed the
 * local cache; from then on {@code platform.feature-flags.v1} keeps it
 * current.
 *
 * <p>Path-level auth is relaxed to {@code permitAll} in
 * {@link com.medfund.tenancy.config.SecurityConfig}, matching
 * {@link InternalMarketDataConfigController} — this is a service-to-service
 * call on the internal network and is not routed through the API gateway.
 * Flag state is configuration, not tenant data, so there is nothing here a
 * leak would expose beyond which features are switched on.
 */
@RestController
@RequestMapping("/internal/v1/feature-flags")
@RequiredArgsConstructor
@Tag(name = "Internal Feature Flags",
     description = "Startup flag snapshot for the Go services. Internal-only.")
public class InternalFeatureFlagController {

    private final PlatformFeatureFlagService svc;

    @GetMapping
    @Operation(summary = "List every platform feature flag and its enabled state",
            description = "Polled once at startup by each Go service to seed its in-process "
                        + "flag cache. Live changes arrive on the platform.feature-flags.v1 topic.")
    @ApiResponse(responseCode = "200", description = "Flags returned")
    public Flux<InternalFlag> list() {
        return svc.list().map(r -> new InternalFlag(r.key(), Boolean.TRUE.equals(r.enabled())));
    }

    /** Deliberately narrower than the admin DTO: Go only needs key + state. */
    public record InternalFlag(String key, boolean enabled) {}
}
