package com.medfund.shared.security;

import com.medfund.shared.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Resolves the calling user's permissions and stuffs them into the reactive
 * context for downstream consumers ({@code @RequiresPermission} aspect,
 * controller code that wants to check {@code PermissionContext.has(...)}).
 *
 * <p>Runs after {@link com.medfund.shared.tenant.TenantWebFilter} so the
 * tenant context is already in scope when this filter queries the resolver,
 * <em>and</em> after Spring Security's {@code WebFilterChainProxy} (which
 * defaults to order {@code -100}) so the JWT is authenticated and
 * {@link ServerWebExchange#getPrincipal()} resolves to a
 * {@code JwtAuthenticationToken}. Anonymous requests and platform endpoints
 * (no tenant) get an empty set — those rely on Spring Security's role-based
 * gates instead of permissions.
 */
@Slf4j
@Order(Ordered.LOWEST_PRECEDENCE - 100)
@RequiredArgsConstructor
public class PermissionResolverFilter implements WebFilter {

    private final PermissionResolver resolver;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        return exchange.getPrincipal()
                .cast(JwtAuthenticationToken.class)
                .map(JwtAuthenticationToken::getToken)
                .flatMap(this::permissionsFor)
                .switchIfEmpty(Mono.just(Set.<String>of()))
                .flatMap(perms -> chain.filter(exchange)
                        .contextWrite(ctx -> PermissionContext.put(ctx, perms)));
    }

    /**
     * The JWT subject is the Keycloak user UUID, which {@code user_roles.user_id}
     * matches verbatim. Non-UUID subjects (e.g. service accounts that auth via
     * client credentials with a non-UUID sub) get an empty permission set —
     * they should hit role-based gates, not permission gates.
     */
    private Mono<Set<String>> permissionsFor(Jwt jwt) {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            if (tenantId == null) return Mono.just(Set.of());
            // Platform super_admin bypass — mirrors user-service RoleController.myPermissions
            // and the Angular PermissionService.isSuperAdmin() short-circuit. Super_admin has
            // no tenant-scoped user_roles rows, so a strict DB lookup would deny every
            // @RequiresPermission-gated endpoint on their behalf.
            if (isSuperAdmin(jwt)) {
                log.debug("Permission bypass: super_admin sub={}", jwt.getSubject());
                return Mono.just(Permissions.ALL);
            }
            UUID userId;
            try {
                userId = UUID.fromString(jwt.getSubject());
            } catch (IllegalArgumentException e) {
                return Mono.just(Set.of());
            }
            return resolver.resolve(userId);
        });
    }

    /**
     * True when the JWT carries the {@code super_admin} role anywhere Keycloak
     * might place it — realm roles ({@code realm_access.roles}) or client roles
     * ({@code resource_access.<client>.roles}). Different Keycloak client
     * configurations map realm roles into either bucket; check both so the
     * super_admin bypass is placement-independent.
     */
    @SuppressWarnings("unchecked")
    private static boolean isSuperAdmin(Jwt jwt) {
        // Realm roles
        Object realm = jwt.getClaim("realm_access");
        if (realm instanceof Map<?, ?> realmAccess) {
            Object rolesObj = realmAccess.get("roles");
            if (rolesObj instanceof List<?> roles && roles.contains("super_admin")) {
                return true;
            }
        }
        // Client roles — walk every client's roles list
        Object resource = jwt.getClaim("resource_access");
        if (resource instanceof Map<?, ?> resourceAccess) {
            for (Object client : resourceAccess.values()) {
                if (client instanceof Map<?, ?> clientMap) {
                    Object rolesObj = clientMap.get("roles");
                    if (rolesObj instanceof List<?> roles && roles.contains("super_admin")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
