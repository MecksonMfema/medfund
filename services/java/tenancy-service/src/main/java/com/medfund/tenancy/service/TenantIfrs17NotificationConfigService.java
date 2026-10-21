package com.medfund.tenancy.service;

import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.tenancy.dto.AddTenantIfrs17NotificationConfigRequest;
import com.medfund.tenancy.dto.UpdateTenantIfrs17NotificationConfigRequest;
import com.medfund.tenancy.entity.Tenant;
import com.medfund.tenancy.entity.TenantIfrs17NotificationConfig;
import com.medfund.tenancy.repository.TenantIfrs17NotificationConfigRepository;
import com.medfund.tenancy.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * CRUD service for {@code public.tenant_ifrs17_notification_config} — the
 * per-tenant recipient list the notification-service Go dispatcher (§20)
 * reads at fan-out time. Mirrors {@link TenantRaConfigService} shape:
 * list / add / update / delete plus a Rule-2 cross-tenant guard on every
 * mutation.
 *
 * <p>Application-level validation coerces {@code eventType} and
 * {@code deliveryMethod} to upper case + rejects unknown values so callers
 * get a clear 400 rather than a Postgres CHECK violation. Recipient shape
 * (email vs URL) is a soft validation only — the dispatcher does the final
 * parse when it fans out.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantIfrs17NotificationConfigService {

    private static final String ENTITY_TYPE = "TENANT_IFRS17_NOTIFICATION_CONFIG";

    private static final Set<String> ALLOWED_EVENT_TYPES = Set.of(
            "ONEROUS_TRANSITION",
            "CSM_NEGATIVE",
            "LOCKED_IN_CURVE_FALLBACK",
            "IBNR_SUB_JOB_STALE",
            "OPENING_BALANCE_AUTO_DERIVED",
            "ALL");

    private static final Set<String> ALLOWED_DELIVERY_METHODS =
            Set.of("EMAIL", "WEBHOOK", "BOTH");

    private final TenantIfrs17NotificationConfigRepository repository;
    private final TenantRepository tenantRepository;
    private final R2dbcEntityTemplate r2dbcTemplate;
    private final AuditPublisher auditPublisher;

    public Flux<TenantIfrs17NotificationConfig> list(UUID tenantId) {
        return repository.findByTenantIdOrderByEventTypeAscRecipientAsc(tenantId);
    }

    /**
     * Read path used by the notification-service dispatcher. Returns active
     * rows matching both the concrete event type AND the wildcard
     * {@code ALL} event type — a single recipient subscribed to {@code ALL}
     * gets every event without needing five separate rows.
     */
    public Flux<TenantIfrs17NotificationConfig> activeFor(UUID tenantId, String eventType) {
        String type = normaliseEventType(eventType);
        return repository.findByTenantIdAndEventTypeAndIsActiveTrue(tenantId, type)
                .concatWith(repository.findByTenantIdAndEventTypeAndIsActiveTrue(tenantId, "ALL"));
    }

    @Transactional
    public Mono<TenantIfrs17NotificationConfig> add(UUID tenantId,
                                                    AddTenantIfrs17NotificationConfigRequest req,
                                                    String actorId,
                                                    String actorEmail) {
        String eventType = normaliseEventType(req.eventType());
        String deliveryMethod = normaliseDeliveryMethod(req.deliveryMethod());
        String recipient = validateRecipient(deliveryMethod, req.recipient());

        TenantIfrs17NotificationConfig row = new TenantIfrs17NotificationConfig();
        row.setTenantId(tenantId);
        row.setEventType(eventType);
        row.setDeliveryMethod(deliveryMethod);
        row.setRecipient(recipient);
        row.setThrottleMinutes(req.throttleMinutes() != null ? req.throttleMinutes() : 15);
        row.setIsActive(req.isActive() == null || req.isActive());
        row.setUpdatedBy(parseUuid(actorId));
        row.setUpdatedByEmail(actorEmail);
        return r2dbcTemplate.insert(row)
                .flatMap(saved -> publishAudit(saved, null, "CREATE", actorId, actorEmail)
                        .thenReturn(saved));
    }

    @Transactional
    public Mono<TenantIfrs17NotificationConfig> update(UUID tenantId, UUID id,
                                                      UpdateTenantIfrs17NotificationConfigRequest req,
                                                      String actorId, String actorEmail) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new NoSuchElementException(
                        "IFRS 17 notification config not found: " + id)))
                .flatMap(existing -> {
                    if (!existing.getTenantId().equals(tenantId)) {
                        return Mono.<TenantIfrs17NotificationConfig>error(new IllegalArgumentException(
                                "IFRS 17 notification config does not belong to tenant"));
                    }
                    String deliveryMethod = normaliseDeliveryMethod(req.deliveryMethod());
                    // Recipient shape re-validation against the new channel — swapping
                    // BOTH → WEBHOOK on an email address would leave a broken row.
                    validateRecipient(deliveryMethod, existing.getRecipient());

                    TenantIfrs17NotificationConfig snapshot = copy(existing);
                    existing.setDeliveryMethod(deliveryMethod);
                    if (req.throttleMinutes() != null) {
                        existing.setThrottleMinutes(req.throttleMinutes());
                    }
                    if (req.isActive() != null) {
                        existing.setIsActive(req.isActive());
                    }
                    existing.setUpdatedAt(OffsetDateTime.now());
                    existing.setUpdatedBy(parseUuid(actorId));
                    existing.setUpdatedByEmail(actorEmail);
                    return repository.save(existing)
                            .flatMap(saved -> publishAudit(saved, snapshot, "UPDATE", actorId, actorEmail)
                                    .thenReturn(saved));
                });
    }

    @Transactional
    public Mono<Void> delete(UUID tenantId, UUID id, String actorId, String actorEmail) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new NoSuchElementException(
                        "IFRS 17 notification config not found: " + id)))
                .flatMap(existing -> {
                    if (!existing.getTenantId().equals(tenantId)) {
                        return Mono.<TenantIfrs17NotificationConfig>error(new IllegalArgumentException(
                                "IFRS 17 notification config does not belong to tenant"));
                    }
                    return repository.delete(existing)
                            .then(publishAudit(existing, copy(existing), "DELETE", actorId, actorEmail))
                            .thenReturn(existing);
                })
                .then();
    }

    private static String normaliseEventType(String raw) {
        if (raw == null) throw new IllegalArgumentException("eventType is required");
        String n = raw.trim().toUpperCase();
        if (!ALLOWED_EVENT_TYPES.contains(n)) {
            throw new IllegalArgumentException(
                    "eventType must be one of " + ALLOWED_EVENT_TYPES + ", got: " + raw);
        }
        return n;
    }

    private static String normaliseDeliveryMethod(String raw) {
        if (raw == null) throw new IllegalArgumentException("deliveryMethod is required");
        String n = raw.trim().toUpperCase();
        if (!ALLOWED_DELIVERY_METHODS.contains(n)) {
            throw new IllegalArgumentException(
                    "deliveryMethod must be one of " + ALLOWED_DELIVERY_METHODS + ", got: " + raw);
        }
        return n;
    }

    private static String validateRecipient(String deliveryMethod, String recipient) {
        if (recipient == null || recipient.isBlank()) {
            throw new IllegalArgumentException("recipient is required");
        }
        String trimmed = recipient.trim();
        // Soft shape validation — WEBHOOK / BOTH need a URL-ish, EMAIL / BOTH
        // need something looking like an email. BOTH is the strictest: it
        // has to satisfy either check because the dispatcher's per-channel
        // parse fires exactly once per channel.
        boolean wantsUrl = "WEBHOOK".equals(deliveryMethod) || "BOTH".equals(deliveryMethod);
        boolean wantsEmail = "EMAIL".equals(deliveryMethod) || "BOTH".equals(deliveryMethod);
        boolean looksLikeUrl = trimmed.startsWith("http://") || trimmed.startsWith("https://");
        boolean looksLikeEmail = trimmed.contains("@") && trimmed.length() >= 3;
        if ("BOTH".equals(deliveryMethod)) {
            if (!looksLikeUrl && !looksLikeEmail) {
                throw new IllegalArgumentException(
                        "recipient must be an email or webhook URL for deliveryMethod=BOTH");
            }
        } else if (wantsUrl && !looksLikeUrl) {
            throw new IllegalArgumentException(
                    "recipient must start with http:// or https:// for deliveryMethod=WEBHOOK");
        } else if (wantsEmail && !looksLikeEmail) {
            throw new IllegalArgumentException(
                    "recipient must be a valid email address for deliveryMethod=EMAIL");
        }
        return trimmed;
    }

    private TenantIfrs17NotificationConfig copy(TenantIfrs17NotificationConfig src) {
        TenantIfrs17NotificationConfig c = new TenantIfrs17NotificationConfig();
        c.setId(src.getId());
        c.setTenantId(src.getTenantId());
        c.setEventType(src.getEventType());
        c.setDeliveryMethod(src.getDeliveryMethod());
        c.setRecipient(src.getRecipient());
        c.setThrottleMinutes(src.getThrottleMinutes());
        c.setIsActive(src.getIsActive());
        c.setCreatedAt(src.getCreatedAt());
        c.setUpdatedAt(src.getUpdatedAt());
        c.setUpdatedBy(src.getUpdatedBy());
        c.setUpdatedByEmail(src.getUpdatedByEmail());
        return c;
    }

    private Mono<Void> publishAudit(TenantIfrs17NotificationConfig current,
                                    TenantIfrs17NotificationConfig previous,
                                    String action, String actorId, String actorEmail) {
        Map<String, Object> oldMap = previous != null ? toMap(previous) : null;
        Map<String, Object> newMap = "DELETE".equals(action) ? null : toMap(current);
        String[] changed = "UPDATE".equals(action) && oldMap != null
                ? changedFields(oldMap, newMap) : null;

        return tenantRepository.findById(current.getTenantId())
                .map(Tenant::getSlug)
                .defaultIfEmpty("unknown")
                .flatMap(slug -> auditPublisher.publish(AuditEvent.create(
                        current.getTenantId().toString(),
                        ENTITY_TYPE,
                        current.getId().toString(),
                        String.format("Ifrs17NotificationConfig for tenant %s %s → %s (%s)",
                                slug, current.getEventType(), current.getRecipient(),
                                current.getDeliveryMethod()),
                        action,
                        actorId,
                        actorEmail,
                        oldMap,
                        newMap,
                        changed,
                        UUID.randomUUID().toString())));
    }

    private Map<String, Object> toMap(TenantIfrs17NotificationConfig row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("eventType", row.getEventType());
        m.put("deliveryMethod", row.getDeliveryMethod());
        m.put("recipient", row.getRecipient());
        m.put("throttleMinutes", row.getThrottleMinutes());
        m.put("isActive", row.getIsActive());
        return m;
    }

    private String[] changedFields(Map<String, Object> oldMap, Map<String, Object> newMap) {
        return newMap.keySet().stream()
                .filter(k -> !Objects.equals(oldMap.get(k), newMap.get(k)))
                .toArray(String[]::new);
    }

    private UUID parseUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try { return UUID.fromString(s); }
        catch (IllegalArgumentException e) { return null; }
    }
}
