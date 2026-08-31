package com.medfund.tenancy.service;

import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.tenancy.dto.AddTenantRegulatoryRecipientRequest;
import com.medfund.tenancy.dto.UpdateTenantRegulatoryRecipientRequest;
import com.medfund.tenancy.entity.Tenant;
import com.medfund.tenancy.entity.TenantRegulatoryRecipient;
import com.medfund.tenancy.repository.TenantRegulatoryRecipientRepository;
import com.medfund.tenancy.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * CRUD service for {@code public.tenant_regulatory_recipient} — the
 * per-tenant email recipient list the notification-service Go dispatcher
 * reads to fan out regulator-due-date reminders (Phase 16 §0 REG20).
 *
 * <p>Application-level validation normalises event tiers to upper case
 * and rejects unknown values so callers get a clear 400 rather than a
 * Postgres CHECK violation. Rule-2 cross-tenant guard on every mutation.
 * Every mutation emits an {@link AuditEvent} with a friendly
 * {@code entityName} per {@code feedback_audit_entity_name}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantRegulatoryRecipientService {

    private static final String ENTITY_TYPE = "TENANT_REGULATORY_RECIPIENT";

    static final Set<String> ALLOWED_TIERS = Set.of(
            "DUE_DATE_7D",
            "DUE_DATE_1D",
            "DUE_DATE_0D",
            "DUE_DATE_OVERDUE");

    private static final String[] DEFAULT_TIERS = ALLOWED_TIERS.stream()
            .sorted()
            .toArray(String[]::new);

    private final TenantRegulatoryRecipientRepository repository;
    private final TenantRepository tenantRepository;
    private final R2dbcEntityTemplate r2dbcTemplate;
    private final AuditPublisher auditPublisher;

    public Flux<TenantRegulatoryRecipient> list(UUID tenantId) {
        return repository.findByTenantIdOrderByEmailAsc(tenantId);
    }

    /**
     * Read path used by the notification-service dispatcher — every
     * currently-active recipient for the tenant. Tier-subscription filter
     * lives in the dispatcher so it can be tested in Go and stays close to
     * the fan-out logic; the DB-side filter is only {@code is_active}.
     */
    public Flux<TenantRegulatoryRecipient> activeFor(UUID tenantId) {
        return repository.findByTenantIdAndIsActiveTrue(tenantId);
    }

    @Transactional
    public Mono<TenantRegulatoryRecipient> add(UUID tenantId,
                                               AddTenantRegulatoryRecipientRequest req,
                                               String actorId,
                                               String actorEmail) {
        if (actorId == null || actorId.isBlank() || actorEmail == null || actorEmail.isBlank()) {
            return Mono.error(new IllegalArgumentException(
                    "actorId and actorEmail are required — every mutation must be traceable"));
        }
        String email = normaliseEmail(req.email());
        String[] tiers = normaliseTiers(req.subscribedEventTiers());

        TenantRegulatoryRecipient row = new TenantRegulatoryRecipient();
        row.setTenantId(tenantId);
        row.setEmail(email);
        row.setDisplayName(req.displayName());
        row.setSubscribedEventTiers(tiers);
        row.setIsActive(req.isActive() == null || req.isActive());
        row.setActorId(parseUuid(actorId));
        row.setActorEmail(actorEmail);
        return r2dbcTemplate.insert(row)
                .flatMap(saved -> publishAudit(saved, null, "CREATE", actorId, actorEmail)
                        .thenReturn(saved));
    }

    @Transactional
    public Mono<TenantRegulatoryRecipient> update(UUID tenantId, UUID id,
                                                  UpdateTenantRegulatoryRecipientRequest req,
                                                  String actorId, String actorEmail) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new NoSuchElementException(
                        "Regulatory recipient not found: " + id)))
                .flatMap(existing -> {
                    if (!existing.getTenantId().equals(tenantId)) {
                        return Mono.<TenantRegulatoryRecipient>error(new IllegalArgumentException(
                                "Regulatory recipient does not belong to tenant"));
                    }
                    TenantRegulatoryRecipient snapshot = copy(existing);
                    if (req.displayName() != null) {
                        existing.setDisplayName(req.displayName());
                    }
                    if (req.subscribedEventTiers() != null) {
                        existing.setSubscribedEventTiers(normaliseTiers(req.subscribedEventTiers()));
                    }
                    if (req.isActive() != null) {
                        existing.setIsActive(req.isActive());
                    }
                    existing.setUpdatedAt(OffsetDateTime.now());
                    existing.setActorId(parseUuid(actorId));
                    existing.setActorEmail(actorEmail);
                    return repository.save(existing)
                            .flatMap(saved -> publishAudit(saved, snapshot, "UPDATE", actorId, actorEmail)
                                    .thenReturn(saved));
                });
    }

    @Transactional
    public Mono<Void> delete(UUID tenantId, UUID id, String actorId, String actorEmail) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new NoSuchElementException(
                        "Regulatory recipient not found: " + id)))
                .flatMap(existing -> {
                    if (!existing.getTenantId().equals(tenantId)) {
                        return Mono.<TenantRegulatoryRecipient>error(new IllegalArgumentException(
                                "Regulatory recipient does not belong to tenant"));
                    }
                    return repository.delete(existing)
                            .then(publishAudit(existing, copy(existing), "DELETE", actorId, actorEmail))
                            .thenReturn(existing);
                })
                .then();
    }

    private static String normaliseEmail(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("email is required");
        return raw.trim().toLowerCase();
    }

    /**
     * Coerce to upper case, dedupe, reject unknown values. Null/empty
     * defaults to every tier — recipients subscribed to nothing would
     * receive nothing, which is almost never the intent.
     */
    static String[] normaliseTiers(List<String> raw) {
        if (raw == null || raw.isEmpty()) return DEFAULT_TIERS.clone();
        String[] normalised = raw.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(String::toUpperCase)
                .distinct()
                .toArray(String[]::new);
        if (normalised.length == 0) return DEFAULT_TIERS.clone();
        for (String tier : normalised) {
            if (!ALLOWED_TIERS.contains(tier)) {
                throw new IllegalArgumentException(
                        "subscribedEventTiers must be a subset of " + ALLOWED_TIERS + ", got: " + tier);
            }
        }
        return normalised;
    }

    private TenantRegulatoryRecipient copy(TenantRegulatoryRecipient src) {
        TenantRegulatoryRecipient c = new TenantRegulatoryRecipient();
        c.setId(src.getId());
        c.setTenantId(src.getTenantId());
        c.setEmail(src.getEmail());
        c.setDisplayName(src.getDisplayName());
        c.setSubscribedEventTiers(src.getSubscribedEventTiers() != null
                ? src.getSubscribedEventTiers().clone() : null);
        c.setIsActive(src.getIsActive());
        c.setCreatedAt(src.getCreatedAt());
        c.setUpdatedAt(src.getUpdatedAt());
        c.setActorId(src.getActorId());
        c.setActorEmail(src.getActorEmail());
        return c;
    }

    private Mono<Void> publishAudit(TenantRegulatoryRecipient current,
                                    TenantRegulatoryRecipient previous,
                                    String action, String actorId, String actorEmail) {
        Map<String, Object> oldMap = previous != null ? toMap(previous) : null;
        Map<String, Object> newMap = "DELETE".equals(action) ? null : toMap(current);
        String[] changed = "UPDATE".equals(action) && oldMap != null && newMap != null
                ? changedFields(oldMap, newMap) : null;
        return tenantRepository.findById(current.getTenantId())
                .map(Tenant::getSlug)
                .defaultIfEmpty("unknown")
                .flatMap(slug -> auditPublisher.publish(AuditEvent.create(
                        current.getTenantId().toString(),
                        ENTITY_TYPE,
                        current.getId().toString(),
                        String.format("Regulator due-date recipient %s for tenant %s",
                                current.getEmail(), slug),
                        action,
                        actorId,
                        actorEmail,
                        oldMap,
                        newMap,
                        changed,
                        UUID.randomUUID().toString())));
    }

    private static Map<String, Object> toMap(TenantRegulatoryRecipient row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("email", row.getEmail());
        m.put("displayName", row.getDisplayName());
        m.put("subscribedEventTiers", row.getSubscribedEventTiers() != null
                ? Arrays.asList(row.getSubscribedEventTiers()) : null);
        m.put("isActive", row.getIsActive());
        return m;
    }

    private static String[] changedFields(Map<String, Object> oldMap, Map<String, Object> newMap) {
        return newMap.keySet().stream()
                .filter(k -> !Objects.equals(oldMap.get(k), newMap.get(k)))
                .toArray(String[]::new);
    }

    private static UUID parseUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try { return UUID.fromString(s); }
        catch (IllegalArgumentException e) { return null; }
    }
}
