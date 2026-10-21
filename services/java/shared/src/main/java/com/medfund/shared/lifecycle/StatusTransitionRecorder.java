package com.medfund.shared.lifecycle;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Phase 13 §A per L2 + L3: writes a single row to {@code member_status_history}
 * or {@code policy_status_history} in the same reactive transaction as the
 * entity update. Called from MemberStatusTransitionService and (Phase 13 §A
 * Phase 3) PolicyStatusTransitionService — never at write sites directly.
 *
 * <p>Lives in {@code shared} so user-service mounts it via the existing
 * {@code com.medfund.shared} component scan; both history tables are
 * tenant-schema tables, so every statement resolves through the
 * TenantAwareConnectionFactory like any other tenant-scoped query.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StatusTransitionRecorder {

    private final DatabaseClient databaseClient;

    /**
     * Append one member transition row. Idempotency per plan invariant #13:
     * natural PK guard is (member_id, effective_at) — callers pass a fresh
     * effectiveAt per transition so replays land as distinct rows only when
     * they genuinely happened at distinct times.
     */
    public Mono<Void> recordMember(UUID memberId, String fromStatus, String toStatus,
                                   OffsetDateTime effectiveAt, UUID actorId, String actorEmail,
                                   String reasonCode, String reasonNote) {
        var spec = databaseClient.sql("""
                INSERT INTO member_status_history
                  (member_id, from_status, to_status, effective_at,
                   actor_id, actor_email, reason_code, reason_note)
                VALUES (:memberId, :fromStatus, :toStatus, :effectiveAt,
                        :actorId, :actorEmail, :reasonCode, :reasonNote)
                """)
                .bind("memberId", memberId)
                .bind("toStatus", toStatus)
                .bind("effectiveAt", effectiveAt);
        spec = bindIfPresent(spec, "fromStatus", fromStatus, String.class);
        spec = bindIfPresent(spec, "actorId", actorId, UUID.class);
        spec = bindIfPresent(spec, "actorEmail", actorEmail, String.class);
        spec = bindIfPresent(spec, "reasonCode", reasonCode, String.class);
        spec = bindIfPresent(spec, "reasonNote", reasonNote, String.class);
        return spec.then();
    }

    /**
     * Append one policy transition row. {@code policySource} uses the
     * V111 CHECK vocabulary (LIFE_POLICY / FUNERAL_POLICY / DISABILITY_POLICY /
     * TRAVEL_POLICY / VEHICLE_POLICY / PROPERTY_POLICY).
     */
    public Mono<Void> recordPolicy(UUID policyId, String policySource,
                                   String fromStatus, String toStatus, OffsetDateTime effectiveAt,
                                   UUID actorId, String actorEmail,
                                   String reasonCode, String reasonNote) {
        var spec = databaseClient.sql("""
                INSERT INTO policy_status_history
                  (policy_id, policy_source, from_status, to_status, effective_at,
                   actor_id, actor_email, reason_code, reason_note)
                VALUES (:policyId, :policySource, :fromStatus, :toStatus, :effectiveAt,
                        :actorId, :actorEmail, :reasonCode, :reasonNote)
                """)
                .bind("policyId", policyId)
                .bind("policySource", policySource)
                .bind("toStatus", toStatus)
                .bind("effectiveAt", effectiveAt);
        spec = bindIfPresent(spec, "fromStatus", fromStatus, String.class);
        spec = bindIfPresent(spec, "actorId", actorId, UUID.class);
        spec = bindIfPresent(spec, "actorEmail", actorEmail, String.class);
        spec = bindIfPresent(spec, "reasonCode", reasonCode, String.class);
        spec = bindIfPresent(spec, "reasonNote", reasonNote, String.class);
        return spec.then();
    }

    /**
     * spring-r2dbc's GenericExecuteSpec has no bindNullable in 6.1.x — an
     * unbound named parameter surfaces as "binding parameter missing", so
     * nullable columns must be explicitly bound NULL with their type.
     */
    private static <T> DatabaseClient.GenericExecuteSpec bindIfPresent(
            DatabaseClient.GenericExecuteSpec spec, String name, T value, Class<T> type) {
        return value != null ? spec.bind(name, value) : spec.bindNull(name, type);
    }
}
