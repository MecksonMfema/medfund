package com.medfund.user.client;

import com.medfund.shared.tenant.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.UUID;

/**
 * Reads the tenant's auto-lapse configuration from tenancy-service
 * ({@code /api/v1/tenants/{tenantId}/auto-lapse-config}, V133).
 * Consumed by {@link com.medfund.user.consumer.ArrearsBreachedConsumer}
 * to decide whether to schedule a LAPSED transition and by what
 * grace window.
 *
 * <p>Failure semantics: network / non-2xx / malformed → log +
 * {@link Snapshot#disabled(UUID)}. Auto-lapse defaults to OFF when the
 * config service is unreachable; a missed lapse re-fires next daily
 * arrears sweep, but silently disabling on failure prevents a
 * misconfigured lookup from bricking the consumer.
 *
 * <p>Short 2s timeout + one retry — the consumer runs off a Kafka
 * receive, and blocking longer than 2s inside {@code flatMap} would
 * back up the topic. The value is not latency-critical: worst case is
 * one missed breach that re-fires tomorrow.
 */
@Slf4j
@Component
public class TenantAutoLapseConfigClient {

    private final WebClient http;

    public TenantAutoLapseConfigClient(WebClient.Builder builder,
                                        @Value("${services.tenancy.base-url:http://localhost:8081}") String baseUrl) {
        this.http = builder.baseUrl(baseUrl).build();
    }

    public Mono<Snapshot> get(UUID tenantId) {
        if (tenantId == null) return Mono.just(Snapshot.disabled(null));
        return Mono.deferContextual(ctx -> {
            String tenantHeader = TenantContext.get(ctx);
            return http.get()
                    .uri("/api/v1/tenants/{tenantId}/auto-lapse-config", tenantId)
                    .header("X-Tenant-ID", tenantHeader != null ? tenantHeader : tenantId.toString())
                    .retrieve()
                    .bodyToMono(Snapshot.class)
                    .timeout(Duration.ofSeconds(2))
                    .retry(1)
                    .defaultIfEmpty(Snapshot.disabled(tenantId))
                    .onErrorResume(err -> {
                        log.warn("auto-lapse-config lookup failed for tenant {}: {} — treating as disabled",
                                tenantId, err.getMessage());
                        return Mono.just(Snapshot.disabled(tenantId));
                    });
        });
    }

    /**
     * Snapshot of the tenant's auto-lapse config. Mirrors the
     * {@code TenantAutoLapseConfigResponse} record in tenancy-service —
     * we only need enabled + threshold + grace here, so the client's
     * value type is a leaner shape (no updatedBy* fields).
     */
    public record Snapshot(UUID tenantId,
                           boolean enabled,
                           Integer arrearsThresholdMonths,
                           Integer graceWindowDays) {

        public static Snapshot disabled(UUID tenantId) {
            return new Snapshot(tenantId, false, null, null);
        }
    }
}
