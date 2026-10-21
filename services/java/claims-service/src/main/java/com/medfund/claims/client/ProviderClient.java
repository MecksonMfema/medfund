package com.medfund.claims.client;

import com.medfund.shared.tenant.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Phase 13 §C Phase 9 per L13 + grill note 6. Batched provider metadata
 * lookup for PROVIDER_NETWORK_UTILIZATION.
 *
 * <p>Cap N=100 per HTTP call. Peer-down semantics: if user-service returns
 * 5xx or times out (2s), the batch returns placeholder
 * {@code (id, "Provider Unknown", "STANDARD")} + a warning gets pushed
 * into the sink so the envelope can surface it to the tenant admin.
 */
@Slf4j
@Component
public class ProviderClient {

    static final int BATCH_SIZE = 100;
    static final Duration TIMEOUT = Duration.ofSeconds(2);

    private final WebClient webClient;

    public ProviderClient(@Value("${medfund.user.base-url:http://localhost:8082}") String baseUrl) {
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    public record ProviderMetadata(UUID id, String name, String networkTier) {}

    /**
     * Look up provider metadata for the given ids. {@code warningsSink}
     * receives a human-readable line whenever a batch fails — callers
     * can then include it in the report envelope's warnings list.
     * Placeholder rows always populate every requested id so downstream
     * enrichment never sees a NULL.
     */
    public Mono<Map<UUID, ProviderMetadata>> batchLookup(Set<UUID> providerIds, List<String> warningsSink) {
        if (providerIds == null || providerIds.isEmpty()) return Mono.just(Map.of());
        List<List<UUID>> batches = partition(providerIds, BATCH_SIZE);
        return Flux.fromIterable(batches)
                .flatMap(batch -> lookupBatch(batch, warningsSink))
                .reduce(new HashMap<UUID, ProviderMetadata>(), (acc, m) -> { acc.putAll(m); return acc; });
    }

    private Mono<Map<UUID, ProviderMetadata>> lookupBatch(List<UUID> ids, List<String> warningsSink) {
        String csv = ids.stream().map(UUID::toString).collect(Collectors.joining(","));
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            return currentBearerToken().flatMap(token -> {
                WebClient.RequestHeadersSpec<?> spec = webClient.get()
                        .uri(uriBuilder -> uriBuilder.path("/api/v1/providers")
                                .queryParam("ids", csv).build())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
                if (tenantId != null) {
                    spec = spec.header("X-Tenant-ID", tenantId);
                }
                return spec.retrieve()
                        .bodyToFlux(ProviderMetadata.class)
                        .timeout(TIMEOUT)
                        .collectMap(ProviderMetadata::id)
                        .map(m -> (Map<UUID, ProviderMetadata>) m)
                        .onErrorResume(err -> fallback(ids, warningsSink, err.getMessage()));
            }).switchIfEmpty(fallback(ids, warningsSink, "no bearer token in reactive security context"));
        });
    }

    private static Mono<Map<UUID, ProviderMetadata>> fallback(List<UUID> ids, List<String> warningsSink,
                                                              String cause) {
        log.warn("ProviderClient batch lookup failed for {} ids: {}", ids.size(), cause);
        if (warningsSink != null) {
            warningsSink.add(String.format("provider metadata unavailable for %d providers", ids.size()));
        }
        Map<UUID, ProviderMetadata> fallback = new HashMap<>();
        ids.forEach(id -> fallback.put(id, new ProviderMetadata(id, "Provider Unknown", "STANDARD")));
        return Mono.just(fallback);
    }

    private static Mono<String> currentBearerToken() {
        return ReactiveSecurityContextHolder.getContext()
                .map(sc -> sc.getAuthentication())
                .filter(JwtAuthenticationToken.class::isInstance)
                .map(auth -> ((JwtAuthenticationToken) auth).getToken())
                .map(Jwt::getTokenValue);
    }

    private static <T> List<List<T>> partition(Collection<T> items, int size) {
        List<T> all = new ArrayList<>(items);
        List<List<T>> parts = new ArrayList<>();
        for (int i = 0; i < all.size(); i += size) {
            parts.add(all.subList(i, Math.min(i + size, all.size())));
        }
        return parts;
    }
}
