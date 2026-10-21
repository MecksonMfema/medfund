package com.medfund.tenancy.controller;

import com.medfund.tenancy.dto.PublicBrandingResponse;
import com.medfund.tenancy.service.PlatformSettingsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Unauthenticated slice of platform settings, exposed so the Angular pre-auth
 * shell can render the platform's branding on the login screen before a JWT
 * exists. See {@link com.medfund.tenancy.config.SecurityConfig} for the
 * {@code /api/v1/public/**} permitAll path matcher.
 */
@RestController
@RequestMapping("/api/v1/public/platform")
@RequiredArgsConstructor
@Tag(name = "Public Branding", description = "Unauthenticated platform branding for the pre-auth UI")
public class PublicBrandingController {

    private final PlatformSettingsService svc;

    @GetMapping("/branding")
    @Operation(summary = "Get platform branding for pre-auth surfaces")
    public Mono<PublicBrandingResponse> get() {
        return svc.get().map(PublicBrandingResponse::from);
    }

    @GetMapping("/logo")
    @Operation(summary = "Stream the platform logo bytes; 404 if no logo has been uploaded")
    public Mono<ResponseEntity<byte[]>> logo() {
        return svc.loadLogo()
                .map(payload -> {
                    HttpHeaders headers = new HttpHeaders();
                    headers.setContentType(payload.mime() != null
                            ? MediaType.parseMediaType(payload.mime())
                            : MediaType.APPLICATION_OCTET_STREAM);
                    headers.setCacheControl("public, max-age=300");
                    return new ResponseEntity<>(payload.bytes(), headers, HttpStatus.OK);
                })
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }
}
