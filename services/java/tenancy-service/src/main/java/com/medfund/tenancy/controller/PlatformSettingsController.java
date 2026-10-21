package com.medfund.tenancy.controller;

import com.medfund.shared.audit.AuditActor;
import com.medfund.tenancy.dto.PlatformSettingsResponse;
import com.medfund.tenancy.dto.UpdatePlatformSettingsRequest;
import com.medfund.tenancy.service.PlatformSettingsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Super-admin platform-wide configuration surface. Every mutation emits an
 * audit event with the caller's identity. Role enforcement happens at the
 * gateway ({@code middleware.RequireSuperAdmin()}); the Java layer just
 * requires a valid JWT so {@link AuditActor} can extract the actor identity.
 */
@RestController
@RequestMapping("/api/v1/platform/settings")
@RequiredArgsConstructor
@Tag(name = "Platform Settings", description = "Super-admin platform-wide configuration")
@SecurityRequirement(name = "bearer-jwt")
public class PlatformSettingsController {

    private final PlatformSettingsService svc;

    @GetMapping
    @Operation(summary = "Get platform settings")
    public Mono<PlatformSettingsResponse> get() {
        return svc.get().map(PlatformSettingsResponse::from);
    }

    @PutMapping
    @Operation(summary = "Update platform settings",
            description = "Patch payload; null fields are left untouched, non-null fields overwrite.")
    public Mono<PlatformSettingsResponse> update(@Valid @RequestBody UpdatePlatformSettingsRequest req,
                                                 @AuthenticationPrincipal Jwt jwt) {
        return svc.update(req, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(PlatformSettingsResponse::from);
    }

    @PostMapping(value = "/logo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload platform logo",
            description = "Accepts SVG, PNG, or JPEG up to 2MB. Bytes are stored on the "
                    + "singleton settings row and served via GET /api/v1/public/platform/logo.")
    public Mono<Map<String, String>> uploadLogo(@RequestPart("file") FilePart file,
                                                @AuthenticationPrincipal Jwt jwt) {
        return svc.uploadLogo(file, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(url -> Map.of("logoUrl", url));
    }
}
