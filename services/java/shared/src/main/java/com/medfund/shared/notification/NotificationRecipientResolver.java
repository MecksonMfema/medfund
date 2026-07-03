package com.medfund.shared.notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.util.context.Context;

import java.util.List;
import java.util.UUID;

/**
 * Resolves the recipient list for a fan-out notification — every user in
 * a tenant who holds at least one of the supplied permissions. Used by
 * the scheduled-job start/finish hooks in {@link com.medfund.shared.scheduler.JobEventPublisher}
 * so an unattended cron run notifies the people who actually run
 * contributions rather than the synthetic SYSTEM actor.
 *
 * <p>The join uses <em>unqualified</em> table names (per
 * bug_public_prefix_silent_rollback) so
 * {@code TenantAwareConnectionFactory} routes the query to the tenant's
 * own schema. The caller MUST propagate the tenant id via reactor
 * context — {@link #forPermissions(String, List)} sets it locally if
 * not already present so scheduled ticks (which run outside any web
 * request) still land on the right schema.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationRecipientResolver {

    private final DatabaseClient db;

    /**
     * Distinct set of user_ids in the tenant that carry at least one of
     * the supplied permission strings via any of their assigned roles.
     * Returns an empty Flux when no roles match — a fan-out with no
     * recipients simply writes zero rows.
     */
    public Flux<UUID> forPermissions(String tenantId, List<String> permissions) {
        if (tenantId == null || tenantId.isBlank() || permissions == null || permissions.isEmpty()) {
            return Flux.empty();
        }
        String sql = """
            SELECT DISTINCT ur.user_id
              FROM user_roles ur
              JOIN role_permissions rp ON rp.role_id = ur.role_id
             WHERE rp.permission IN (:permissions)
            """;
        return db.sql(sql)
                .bind("permissions", permissions)
                .map((row, meta) -> row.get("user_id", UUID.class))
                .all()
                // Ensure the tenant context is set even when the caller is a
                // background scheduler thread with no upstream context.
                .contextWrite(ctx -> ctx.put("TENANT_ID", tenantId))
                .doOnError(e -> log.warn("Failed to resolve notification recipients for tenant {}: {}",
                        tenantId, e.getMessage()))
                .onErrorResume(e -> Flux.empty());
    }
}
