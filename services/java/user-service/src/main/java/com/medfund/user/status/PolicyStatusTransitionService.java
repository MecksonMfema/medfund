package com.medfund.user.status;

import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.lifecycle.PolicyReasonCode;
import com.medfund.shared.lifecycle.StatusTransitionRecorder;
import com.medfund.shared.tenant.TenantContext;
import com.medfund.user.publisher.PolicyStatusChangedPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Phase 13 §A per L3 + L4 + L5. Uniform 4-action write pathway per
 * annual-bind policy entity (lapse / terminate / suspend / reinstate).
 * Concrete subclasses provide the policy-source identity, repository access,
 * and entity accessors — the transition contract itself lives here once:
 *
 * <ol>
 *   <li>validate action → target status + per-line reason vocab</li>
 *   <li>load policy, skip same-status no-ops</li>
 *   <li>write {@code policy_status_history} row, then save the entity —
 *       both inside one reactive transaction, record BEFORE save (a failed
 *       history insert must drag the entity update back)</li>
 *   <li>emit an {@code AUDIT} event with from/to/reasonCode</li>
 *   <li>publish the {@code medfund.user.policy-status-changed} Kafka event
 *       (Phase 13 §B per L6) so contributions-service can update
 *       {@code earning_schedule} to keep UPR correct</li>
 * </ol>
 */
@Slf4j
@RequiredArgsConstructor
public abstract class PolicyStatusTransitionService<T> {

    protected final R2dbcRepository<T, UUID> repository;
    protected final StatusTransitionRecorder recorder;
    protected final AuditPublisher auditPublisher;
    protected final PolicyStatusChangedPublisher policyStatusChangedPublisher;
    protected final TransactionalOperator tx;

    /** Concrete subclass identity — e.g. "LIFE_POLICY". Drives vocab validation + audit entityType. */
    protected abstract String policySource();

    /**
     * InsuranceLine enum name — "LIFE" / "FUNERAL" / "DISABILITY" / "TRAVEL" /
     * "VEHICLE" / "PROPERTY". Carried on the policy-status-changed event so
     * consumers can filter without a policy-source→line lookup table.
     */
    protected abstract String insuranceLine();

    /** Read the entity's current status field. */
    protected abstract String getStatus(T entity);

    /** Mutate the entity's status field in place and return it. */
    protected abstract T setStatus(T entity, String newStatus);

    /** Read the entity's ID. */
    protected abstract UUID getId(T entity);

    /**
     * Human-readable business identifier for audit listings — e.g. the
     * policy number. May be null on legacy rows; callers fall back to the ID
     * so audit events never carry a null entityName (see AuditEvent.create).
     */
    protected abstract String getEntityName(T entity);

    /** Destination statuses reachable through the uniform 4-action surface. */
    protected static final Set<String> VALID_DESTINATIONS =
            Set.of("active", "lapsed", "suspended", "terminated");

    public Mono<T> transition(UUID policyId, String action, String reasonCode, String reasonNote,
                              String actorId, String actorEmail) {
        if (actorEmail == null || actorEmail.isBlank()) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "actorEmail is required for a policy status transition (policy " + policyId + ")"));
        }
        String newStatus;
        try {
            newStatus = actionToStatus(action);
        } catch (IllegalArgumentException e) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage()));
        }
        if (!VALID_DESTINATIONS.contains(newStatus)) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Invalid target status: " + newStatus));
        }
        // Cheap reject before any DB round-trip; also gives invalid-vocab 400s
        // even when the policy id doesn't exist (vocab errors win — they're
        // about the request shape, not the resource).
        if (reasonCode != null && !PolicyReasonCode.isValid(policySource(), reasonCode)) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid reason_code for " + policySource() + ": " + reasonCode));
        }
        return repository.findById(policyId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Policy not found: " + policyId)))
                .flatMap(existing -> applyTransition(existing, action, newStatus,
                        reasonCode, reasonNote, actorId, actorEmail))
                .as(tx::transactional);
    }

    private Mono<T> applyTransition(T existing, String action, String newStatus,
                                    String reasonCode, String reasonNote,
                                    String actorId, String actorEmail) {
        String oldStatus = getStatus(existing);
        if (newStatus.equals(oldStatus)) {
            log.debug("Skipping same-status {} transition {} ({} → {})",
                    policySource(), getId(existing), oldStatus, newStatus);
            return Mono.just(existing);
        }
        setStatus(existing, newStatus);
        OffsetDateTime effectiveAt = OffsetDateTime.now();
        return recorder.recordPolicy(getId(existing), policySource(), oldStatus, newStatus,
                        effectiveAt, safeParseUuid(actorId), actorEmail, reasonCode, reasonNote)
                // Deferred so the save is only assembled once the history row
                // is durably recorded — same ordering guard as the member pathway.
                .then(Mono.defer(() -> repository.save(existing)))
                .flatMap(saved -> publishAudit(action.toLowerCase(), oldStatus, newStatus,
                        saved, actorId, actorEmail, reasonCode))
                .flatMap(saved -> publishPolicyStatusChanged(saved, oldStatus, newStatus,
                        effectiveAt, reasonCode, actorId, actorEmail));
    }

    private Mono<T> publishAudit(String action, String oldStatus, String newStatus, T saved,
                                 String actorId, String actorEmail, String reasonCode) {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            Map<String, Object> newValue = new HashMap<>();
            newValue.put("status", newStatus);
            if (reasonCode != null) {
                newValue.put("reasonCode", reasonCode);
            }
            String entityName = getEntityName(saved);
            var event = AuditEvent.create(
                    tenantId != null ? tenantId : "unknown",
                    policySource(),
                    getId(saved).toString(),
                    entityName != null ? entityName : getId(saved).toString(),
                    action,
                    actorId,
                    actorEmail,
                    Map.of("status", oldStatus),
                    newValue,
                    new String[]{"status"},
                    UUID.randomUUID().toString());
            return auditPublisher.publish(event).thenReturn(saved);
        });
    }

    private Mono<T> publishPolicyStatusChanged(T saved, String oldStatus, String newStatus,
                                               OffsetDateTime effectiveAt, String reasonCode,
                                               String actorId, String actorEmail) {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            return policyStatusChangedPublisher.publish(
                            tenantId,
                            getId(saved),
                            policySource(),
                            insuranceLine(),
                            oldStatus,
                            newStatus,
                            effectiveAt,
                            reasonCode,
                            actorId,
                            actorEmail)
                    .thenReturn(saved);
        });
    }

    /** JWT subs are normally UUIDs; system/daemon actors may not be. Null out unparseable ids. */
    private static UUID safeParseUuid(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** URL action names → target status (per L5 uniform 4-action shape). */
    private static String actionToStatus(String action) {
        if (action == null) {
            throw new IllegalArgumentException("Action is required");
        }
        return switch (action.toLowerCase()) {
            case "lapse"     -> "lapsed";
            case "terminate" -> "terminated";
            case "suspend"   -> "suspended";
            case "reinstate" -> "active";
            default -> throw new IllegalArgumentException("Unknown action: " + action);
        };
    }
}
