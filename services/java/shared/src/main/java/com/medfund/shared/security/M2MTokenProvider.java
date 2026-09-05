package com.medfund.shared.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 17 §A.3 — mints Keycloak client-credentials tokens for service-to-
 * service calls. Finance-service uses this to acquire {@code
 * scheduled_report:render}-scoped tokens before invoking owner-service
 * render endpoints; without it, the {@code @RequiresPermission} gate on
 * those endpoints would 403.
 *
 * <p>Tokens are cached per requested scope until 30 seconds before their
 * Keycloak-declared expiry to avoid a per-call token round-trip. The cache
 * is process-local — a rolling deploy across finance-service instances
 * simply re-mints on demand.
 *
 * <p>The bean only wires when {@code keycloak.m2m.client-id} is set;
 * services without an M2M client (unit-test slices, single-service dev)
 * simply don't get one. Callers should inject via {@code
 * Optional<M2MTokenProvider>} and fail closed if absent.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "keycloak.m2m.client-id")
public class M2MTokenProvider {

    private final WebClient webClient;
    private final String tokenEndpoint;
    private final String clientId;
    private final String clientSecret;

    private final Map<String, CachedToken> cache = new ConcurrentHashMap<>();

    public M2MTokenProvider(WebClient.Builder webClientBuilder,
                            @Value("${keycloak.token-endpoint}") String tokenEndpoint,
                            @Value("${keycloak.m2m.client-id}") String clientId,
                            @Value("${keycloak.m2m.client-secret}") String clientSecret) {
        this.webClient = webClientBuilder.build();
        this.tokenEndpoint = tokenEndpoint;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    /**
     * Fetch a valid service token for the given scope. Returns a cached
     * token when one exists and has more than 30 seconds of life left;
     * otherwise mints a fresh one.
     */
    public Mono<String> getServiceToken(String scope) {
        CachedToken cached = cache.get(scope);
        if (cached != null && cached.isValid()) {
            return Mono.just(cached.token);
        }
        return fetchToken(scope)
                .doOnNext(t -> cache.put(scope, t))
                .map(t -> t.token);
    }

    private Mono<CachedToken> fetchToken(String scope) {
        String body = "grant_type=client_credentials"
                + "&scope=" + scope
                + "&client_id=" + clientId
                + "&client_secret=" + clientSecret;
        return webClient.post()
                .uri(tokenEndpoint)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .map(json -> new CachedToken(
                        (String) json.get("access_token"),
                        Instant.now().plusSeconds(
                                ((Number) json.getOrDefault("expires_in", 300)).longValue() - 30)))
                .doOnNext(t -> log.debug("[m2m-token] minted new token scope={} expiry={}",
                        scope, t.expiry))
                .doOnError(err -> log.error("[m2m-token] fetch failed scope={}: {}",
                        scope, err.getMessage()));
    }

    record CachedToken(String token, Instant expiry) {
        boolean isValid() {
            return token != null && Instant.now().isBefore(expiry);
        }
    }
}
