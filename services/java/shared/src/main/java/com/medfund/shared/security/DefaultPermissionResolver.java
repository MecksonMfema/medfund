package com.medfund.shared.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.medfund.shared.tenant.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Per-process Caffeine-cached implementation of {@link PermissionResolver}.
 *
 * <p>Cache key is {@code "{tenantId}:{userId}"} so two tenants' resolutions
 * never collide. TTL is 60 s — short enough that a tenant admin's
 * role-permission edit takes effect quickly without explicit invalidation,
 * long enough that the hot-path doesn't hit the DB on every request.
 *
 * <p>Phase 1.6 adds Kafka-driven invalidation via {@link #invalidate}/{@link #invalidateTenant}
 * for sub-TTL responsiveness across the cluster.
 */
@Slf4j
public class DefaultPermissionResolver implements PermissionResolver {

    private static final String SQL =
            "SELECT DISTINCT rp.permission " +
            "FROM role_permissions rp " +
            "JOIN user_roles ur ON ur.role_id = rp.role_id " +
            "WHERE ur.user_id = :userId";

    private final DatabaseClient db;
    private final Cache<String, Set<String>> cache;

    public DefaultPermissionResolver(DatabaseClient db) {
        this.db = db;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(60))
                .maximumSize(10_000)
                .build();
    }

    @Override
    public Mono<Set<String>> resolve(UUID userId) {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            // No tenant in scope = platform endpoint — permission system doesn't apply.
            if (tenantId == null || tenantId.isBlank()) {
                return Mono.just(Set.of());
            }
            String cacheKey = tenantId + ":" + userId;
            Set<String> cached = cache.getIfPresent(cacheKey);
            if (cached != null) {
                return Mono.just(cached);
            }
            return db.sql(SQL)
                    .bind("userId", userId)
                    .map((row, meta) -> row.get("permission", String.class))
                    .all()
                    .collect(Collectors.toCollection(HashSet<String>::new))
                    .map(DefaultPermissionResolver::applyCompatMappings)
                    .map(Set::copyOf)
                    .doOnNext(set -> cache.put(cacheKey, set))
                    .onErrorResume(e -> {
                        // Failure here should not 500 the request — log and treat as "no permissions"
                        // so the caller hits @RequiresPermission and gets a clean 403, not a stack trace.
                        log.warn("Permission resolution failed for tenant={} user={}: {}",
                                tenantId, userId, e.getMessage());
                        return Mono.just(Set.of());
                    });
        });
    }

    /**
     * COMPAT (V074 → next release): tenants whose role assignments still
     * carry the legacy flat {@link Permissions#FINANCE_POST_ADJUSTMENTS}
     * key automatically pick up all three of the new granular
     * {@code finance.notes:*} permissions. Applied after DB fetch so the
     * cache holds the expanded set — that means downstream authorization
     * checks against the new keys succeed without any tenant role-edit
     * on cutover day (which is what Phase 3 of the notes-rename plan
     * needs). Remove this once all tenants have been migrated.
     *
     * <p>Package-private so {@code DefaultPermissionResolverTest} can
     * exercise the mapping in isolation.
     */
    static HashSet<String> applyCompatMappings(HashSet<String> permissions) {
        if (permissions.contains(Permissions.FINANCE_POST_ADJUSTMENTS)) {
            permissions.add(Permissions.FINANCE_NOTES_READ);
            permissions.add(Permissions.FINANCE_NOTES_WRITE);
            permissions.add(Permissions.FINANCE_NOTES_APPROVE);
        }
        return permissions;
    }

    @Override
    public void invalidate(String tenantId, UUID userId) {
        cache.invalidate(tenantId + ":" + userId);
    }

    @Override
    public void invalidateTenant(String tenantId) {
        // No prefix-scan API on Caffeine — walk the keyset. Cheap given maximumSize 10k.
        String prefix = tenantId + ":";
        cache.asMap().keySet().removeIf(k -> k.startsWith(prefix));
    }
}
