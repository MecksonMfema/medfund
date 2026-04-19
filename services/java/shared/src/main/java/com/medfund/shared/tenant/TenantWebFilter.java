package com.medfund.shared.tenant;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Extracts X-Tenant-ID header and stores in Reactor context.
 * Rejects requests without a tenant ID (except health/actuator endpoints).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TenantWebFilter implements WebFilter {

    // Paths that live in the public schema or aggregate across tenants —
    // no X-Tenant-ID required for these.
    private static final java.util.List<String> PLATFORM_PATHS = java.util.List.of(
            "/actuator",
            "/swagger",
            "/v3/api-docs",
            "/api/v1/staff-users",
            "/api/v1/tenants",
            "/api/v1/platform",
            "/api/v1/roles",
            "/api/v1/scheduled-jobs",
            "/api/v1/plans",
            "/api/v1/quotes"
    );

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();

        // Skip tenant resolution for platform-level paths
        if (PLATFORM_PATHS.stream().anyMatch(path::startsWith)) {
            return chain.filter(exchange);
        }

        String tenantId = exchange.getRequest().getHeaders().getFirst("X-Tenant-ID");
        if (tenantId == null || tenantId.isBlank()) {
            exchange.getResponse().setStatusCode(HttpStatus.BAD_REQUEST);
            return exchange.getResponse().setComplete();
        }

        return chain.filter(exchange)
                .contextWrite(ctx -> TenantContext.put(ctx, tenantId));
    }
}
