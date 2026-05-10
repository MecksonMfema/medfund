package com.medfund.finance.util;

import java.util.UUID;

/**
 * Helpers for handling the {@code Principal#getName()} string passed
 * around as {@code actorId} in finance services.
 *
 * <p>Different Keycloak realm configurations map the JWT subject to
 * different things — sometimes the user UUID, sometimes the preferred
 * username, sometimes a service-account name. We can't assume the
 * value is always a parseable UUID, so audit-stamp helpers should fall
 * back to {@code null} rather than throw.
 */
public final class Actors {

    private Actors() {}

    /**
     * Parse {@code actorId} as a {@link UUID}, returning {@code null} when
     * it's missing or not a valid UUID. Use to populate audit columns
     * like {@code created_by} / {@code updated_by} without risking an
     * {@link IllegalArgumentException} on non-UUID principal names.
     */
    public static UUID parseId(String actorId) {
        if (actorId == null || actorId.isBlank()) return null;
        try {
            return UUID.fromString(actorId);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
