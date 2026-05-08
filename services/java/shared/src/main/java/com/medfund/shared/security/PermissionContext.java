package com.medfund.shared.security;

import reactor.util.context.Context;
import reactor.util.context.ContextView;

import java.util.Set;

/**
 * Stores the calling user's effective permissions in the Reactor context.
 * {@link PermissionResolverFilter} populates this on every authenticated
 * request; {@code @RequiresPermission}'s aspect reads it to enforce gates.
 *
 * <p>The set is empty when a request is anonymous, when no tenant is in
 * scope (platform endpoints), or when the user has no role assignments.
 */
public final class PermissionContext {

    public static final String KEY = "MEDFUND_PERMISSIONS";

    private PermissionContext() {}

    public static Context put(Context ctx, Set<String> permissions) {
        return ctx.put(KEY, permissions);
    }

    public static Set<String> get(ContextView ctx) {
        return ctx.getOrDefault(KEY, Set.of());
    }

    public static boolean has(ContextView ctx, String permission) {
        return get(ctx).contains(permission);
    }
}
