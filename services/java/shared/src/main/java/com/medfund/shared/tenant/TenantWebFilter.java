package com.medfund.shared.tenant;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * Extracts X-Tenant-ID header and stores in Reactor context.
 * Rejects requests without a tenant ID (except health/actuator endpoints).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TenantWebFilter implements WebFilter {

    // Paths that live in the public schema or aggregate across tenants —
    // no X-Tenant-ID required for these.
    //
    // Providers are platform-wide: a single doctor / clinic / pharmacy serves
    // members of every tenant, so the registry is shared and never partitioned
    // by tenant. Audit events are accessed cross-tenant from the platform
    // admin portal; filtering down to a tenant happens at the query layer.
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
            "/api/v1/quotes",
            "/api/v1/tenant-stats",
            "/api/v1/providers",
            "/api/v1/audit",
            // Notifications are scoped to the JWT subject, not the tenant.
            // A super admin without an active tenant still needs to read
            // their bell, and per-user rows already carry tenant_id for
            // downstream filtering.
            "/api/v1/notifications"
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
            return missingTenantResponse(exchange, path);
        }

        return chain.filter(exchange)
                .contextWrite(ctx -> TenantContext.put(ctx, tenantId));
    }

    /**
     * Writes a {@code ProblemDetail}-shaped JSON body so the failure is
     * self-explanatory in browser dev tools instead of an empty 400. Built
     * by hand because we are below the controller layer — Spring's
     * {@code @ExceptionHandler} machinery is not in play yet.
     */
    private Mono<Void> missingTenantResponse(ServerWebExchange exchange, String path) {
        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.BAD_REQUEST);
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);

        String body = "{"
                + "\"type\":\"https://medfund.healthcare/errors/missing-tenant\","
                + "\"title\":\"Tenant Required\","
                + "\"status\":400,"
                + "\"detail\":\"Missing X-Tenant-ID header. This endpoint is tenant-scoped; "
                + "platform-level endpoints (tenants, staff-users, providers, audit, …) do not require one.\","
                + "\"instance\":\"" + escapeJson(path) + "\""
                + "}";

        DataBufferFactory factory = response.bufferFactory();
        DataBuffer buffer = factory.wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
