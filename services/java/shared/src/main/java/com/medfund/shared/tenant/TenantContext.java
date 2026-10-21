package com.medfund.shared.tenant;

import reactor.util.context.Context;
import reactor.util.context.ContextView;

import java.util.UUID;

/**
 * Reactive tenant context — stores tenant ID in Project Reactor context (not ThreadLocal).
 * Used by TenantWebFilter and TenantAwareConnectionFactory.
 */
public final class TenantContext {

    public static final String KEY = "TENANT_ID";

    private TenantContext() {}

    public static Context put(Context ctx, String tenantId) {
        return ctx.put(KEY, tenantId);
    }

    public static String get(ContextView ctx) {
        return ctx.getOrDefault(KEY, null);
    }

    /**
     * Tenant ID as a UUID, for queries that filter a platform table by
     * tenant membership (CLAUDE.md Critical Rule 2, "platform tables with
     * tenant membership"). Throws when the context carries no tenant, or
     * carries the non-UUID {@code "platform"} sentinel: such a query has no
     * safe answer, and quietly binding null would render as "no rows" rather
     * than "no tenant".
     */
    public static UUID requireUuid(ContextView ctx) {
        String raw = get(ctx);
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException(
                    "No tenant in Reactor context; this query is tenant-scoped");
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "Tenant in Reactor context is not a UUID: " + raw, e);
        }
    }
}
