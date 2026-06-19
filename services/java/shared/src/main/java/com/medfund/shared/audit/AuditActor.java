package com.medfund.shared.audit;

import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Canonical helpers for extracting actor identity from a Keycloak JWT for
 * audit emission. Every {@link AuditEvent} MUST carry both an {@code actorId}
 * (UUID — for the join back to the users table) and an {@code actorEmail}
 * (or username fallback — for human-readable audit listings).
 *
 * <p>Audit logs that ship only the UUID force every viewer to run a DB join
 * to see who did what; that defeats the point of an audit feed. Always pass
 * both columns through.
 *
 * <p>Usage in controllers:
 * <pre>{@code
 * @PostMapping
 * public Mono<FooResponse> create(@Valid @RequestBody CreateFooRequest req,
 *                                 @AuthenticationPrincipal Jwt jwt) {
 *     return fooService.create(req, AuditActor.id(jwt), AuditActor.email(jwt))
 *                      .map(FooResponse::from);
 * }
 * }</pre>
 *
 * <p>For service-internal flows that have no JWT (scheduled jobs, kafka
 * consumers, system-initiated actions), use {@link #systemActor()} so the
 * audit log still distinguishes the source.
 */
public final class AuditActor {

    /** Stable identifier used as the actorId for any system-initiated audit event. */
    public static final String SYSTEM_ID = "system";

    /** Display name written to actorEmail for system-initiated audit events. */
    public static final String SYSTEM_EMAIL = "system@medfund";

    private AuditActor() {}

    /**
     * Extract the subject claim (typically the Keycloak user UUID) from a JWT.
     * Returns {@link #SYSTEM_ID} when the JWT is null — common in server-to-server
     * code paths that don't carry user context.
     */
    public static String id(Jwt jwt) {
        return jwt != null ? jwt.getSubject() : SYSTEM_ID;
    }

    /**
     * Extract the human-readable identifier from a JWT — {@code email} claim
     * preferred, {@code preferred_username} as fallback. Returns
     * {@link #SYSTEM_EMAIL} when the JWT is null. Never returns null — audit
     * sinks rely on this being populated so audit lists are scannable without
     * a join.
     */
    public static String email(Jwt jwt) {
        if (jwt == null) return SYSTEM_EMAIL;
        String email = jwt.getClaimAsString("email");
        if (email != null && !email.isBlank()) return email;
        String username = jwt.getClaimAsString("preferred_username");
        if (username != null && !username.isBlank()) return username;
        // Last resort — sub claim is at least stable. Better than null.
        return jwt.getSubject() != null ? jwt.getSubject() : SYSTEM_EMAIL;
    }

    /**
     * Returns the canonical pair used by background jobs, Kafka consumers,
     * and scheduled tasks that emit audit events without user context.
     */
    public static String[] systemActor() {
        return new String[]{SYSTEM_ID, SYSTEM_EMAIL};
    }
}
