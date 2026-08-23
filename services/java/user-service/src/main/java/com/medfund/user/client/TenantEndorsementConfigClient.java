package com.medfund.user.client;

import com.medfund.shared.tenant.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

/**
 * Reads the tenant's endorsement four-eyes configuration from
 * tenancy-service ({@code /api/v1/tenants/{tenantId}/endorsement-config},
 * V134). Consumed by {@link com.medfund.user.endorsement.service.PolicyEndorsementService}
 * to decide whether a DRAFT endorsement should auto-commit or park for
 * a second-actor approval.
 *
 * <p>Failure semantics mirror {@link TenantAutoLapseConfigClient}: network
 * / non-2xx / malformed → log + {@link Snapshot#disabled(UUID)}. Missing
 * config defaults to auto-commit (no four-eyes gate). Silently disabling
 * on failure prevents a misconfigured lookup from bricking the drafting
 * flow; the audit trail still records the actor.
 */
@Slf4j
@Component
public class TenantEndorsementConfigClient {

    private final WebClient http;

    public TenantEndorsementConfigClient(WebClient.Builder builder,
                                          @Value("${services.tenancy.base-url:http://localhost:8081}") String baseUrl) {
        this.http = builder.baseUrl(baseUrl).build();
    }

    public Mono<Snapshot> get(UUID tenantId) {
        if (tenantId == null) return Mono.just(Snapshot.disabled(null));
        return Mono.deferContextual(ctx -> {
            String tenantHeader = TenantContext.get(ctx);
            return http.get()
                    .uri("/api/v1/tenants/{tenantId}/endorsement-config", tenantId)
                    .header("X-Tenant-ID", tenantHeader != null ? tenantHeader : tenantId.toString())
                    .retrieve()
                    .bodyToMono(Snapshot.class)
                    .timeout(Duration.ofSeconds(2))
                    .retry(1)
                    .defaultIfEmpty(Snapshot.disabled(tenantId))
                    .onErrorResume(err -> {
                        log.warn("endorsement-config lookup failed for tenant {}: {} — treating as disabled",
                                tenantId, err.getMessage());
                        return Mono.just(Snapshot.disabled(tenantId));
                    });
        });
    }

    /**
     * Snapshot of the tenant's endorsement config. When {@code enabled=false}
     * or the threshold is null, {@code PolicyEndorsementService} skips the
     * four-eyes gate and auto-commits on create.
     */
    public record Snapshot(UUID tenantId,
                           boolean enabled,
                           BigDecimal fourEyesThresholdAmount,
                           String thresholdCurrency) {

        public static Snapshot disabled(UUID tenantId) {
            return new Snapshot(tenantId, false, null, null);
        }
    }
}
