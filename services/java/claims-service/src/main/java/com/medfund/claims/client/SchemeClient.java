package com.medfund.claims.client;

import com.medfund.shared.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.UUID;

/**
 * Thin reactive client over contributions-service's scheme endpoint.
 * Used by {@link com.medfund.claims.service.ClaimService#submit} to
 * derive the authoritative insurance line for a claim — the client
 * hint on the request is treated as advisory, and the value returned
 * here is the one that gets persisted and echoed in events.
 *
 * <p><b>Fail-closed contract:</b> if the scheme lookup fails (5xx,
 * timeout, scheme missing) the submit is rejected. Unlike the AI
 * client, we cannot fall back to a default — persisting a claim with
 * the wrong insurance line silently poisons downstream ledger writes.
 */
@Component
public class SchemeClient {

    private static final Logger log = LoggerFactory.getLogger(SchemeClient.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final WebClient webClient;

    public SchemeClient(@Value("${medfund.contributions.base-url:http://localhost:8084}") String baseUrl) {
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    public Mono<SchemeSummary> findById(UUID schemeId) {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            return currentBearerToken().flatMap(token -> {
                var spec = webClient.get()
                        .uri("/api/v1/schemes/{id}", schemeId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
                if (tenantId != null) spec = spec.header("X-Tenant-ID", tenantId);
                return spec.retrieve()
                        .bodyToMono(SchemeSummary.class)
                        .timeout(TIMEOUT)
                        .doOnError(e -> log.warn("Scheme lookup failed for {}: {}", schemeId, e.toString()));
            });
        });
    }

    /**
     * Pull the caller's JWT out of the reactive security context and
     * forward it verbatim on the outgoing call. Without this the
     * downstream service rejects the request as unauthenticated —
     * claims-service acts on behalf of the operator, so their JWT
     * (and its tenant + role claims) is the right identity to present.
     */
    private static Mono<String> currentBearerToken() {
        return ReactiveSecurityContextHolder.getContext()
                .map(sc -> sc.getAuthentication())
                .filter(JwtAuthenticationToken.class::isInstance)
                .map(auth -> ((JwtAuthenticationToken) auth).getToken())
                .map(Jwt::getTokenValue);
    }

    /**
     * Minimal projection of contributions-service's SchemeResponse — only
     * the fields the claims flow needs, so the client isn't coupled to the
     * full billing DTO.
     */
    public record SchemeSummary(
            UUID id,
            String name,
            String schemeType,
            String insuranceLine,
            String currencyCode
    ) {}
}
