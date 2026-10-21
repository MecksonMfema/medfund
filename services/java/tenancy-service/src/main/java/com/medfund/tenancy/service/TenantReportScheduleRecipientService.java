package com.medfund.tenancy.service;

import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.tenancy.dto.AddTenantReportScheduleRecipientRequest;
import com.medfund.tenancy.dto.UpdateTenantReportScheduleRecipientRequest;
import com.medfund.tenancy.entity.Tenant;
import com.medfund.tenancy.entity.TenantReportSchedule;
import com.medfund.tenancy.entity.TenantReportScheduleRecipient;
import com.medfund.tenancy.repository.TenantRepository;
import com.medfund.tenancy.repository.TenantReportScheduleRecipientRepository;
import com.medfund.tenancy.repository.TenantReportScheduleRepository;
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
import java.util.UUID;

/**
 * CRUD service for {@code public.tenant_report_schedule_recipient} — mirrors
 * the shape of {@link TenantRegulatoryRecipientService} without the
 * subscribed-event-tier column (Phase 17 delivery does not tier events).
 *
 * <p>The public unsubscribe path is a separate entry point: recipients click
 * the link in the delivery email, land on the Angular {@code /public/unsubscribe/:token}
 * page, and the confirm posts to
 * {@code POST /api/v1/report-schedule-recipients/unsubscribe/{token}} which
 * calls {@link #unsubscribeByToken(UUID, String)}. Audit actor becomes
 * {@code system:unsubscribe} — the human user isn't authenticated at that
 * point but the audit trail still captures the intent.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantReportScheduleRecipientService {

    private static final String ENTITY_TYPE = "TENANT_REPORT_SCHEDULE_RECIPIENT";
    private static final String UNSUBSCRIBE_ACTOR_ID = "system:unsubscribe";
    private static final String UNSUBSCRIBE_ACTOR_EMAIL = "system+unsubscribe@medfund";

    private final TenantReportScheduleRecipientRepository repository;
    private final TenantReportScheduleRepository scheduleRepository;
    private final TenantRepository tenantRepository;
    private final R2dbcEntityTemplate r2dbcTemplate;
    private final AuditPublisher auditPublisher;

    public Flux<TenantReportScheduleRecipient> list(UUID tenantId, UUID scheduleId) {
        return requireSchedule(tenantId, scheduleId)
                .flatMapMany(schedule ->
                        repository.findByScheduleIdOrderByEmailAsc(schedule.getId()));
    }

    @Transactional
    public Mono<TenantReportScheduleRecipient> add(UUID tenantId, UUID scheduleId,
                                                   AddTenantReportScheduleRecipientRequest req,
                                                   String actorId, String actorEmail) {
        if (actorId == null || actorId.isBlank() || actorEmail == null || actorEmail.isBlank()) {
            return Mono.error(new IllegalArgumentException(
                    "actorId and actorEmail are required - every mutation must be traceable"));
        }
        return requireSchedule(tenantId, scheduleId)
                .flatMap(schedule -> {
                    TenantReportScheduleRecipient row = new TenantReportScheduleRecipient();
                    row.setScheduleId(schedule.getId());
                    row.setEmail(normaliseEmail(req.email()));
                    row.setDisplayName(req.displayName());
                    row.setIsActive(req.isActive() == null || req.isActive());
                    row.setActorId(parseUuid(actorId));
                    row.setActorEmail(actorEmail);
                    return r2dbcTemplate.insert(row)
                            .flatMap(saved -> publishAudit(saved, null, "CREATE",
                                    actorId, actorEmail, schedule).thenReturn(saved));
                });
    }

    @Transactional
    public Mono<TenantReportScheduleRecipient> update(UUID tenantId, UUID scheduleId, UUID recipientId,
                                                      UpdateTenantReportScheduleRecipientRequest req,
                                                      String actorId, String actorEmail) {
        return requireSchedule(tenantId, scheduleId)
                .flatMap(schedule -> repository.findById(recipientId)
                        .switchIfEmpty(Mono.error(new NoSuchElementException(
                                "Recipient not found: " + recipientId)))
                        .flatMap(existing -> {
                            if (!existing.getScheduleId().equals(schedule.getId())) {
                                return Mono.<TenantReportScheduleRecipient>error(
                                        new IllegalArgumentException(
                                                "Recipient does not belong to schedule"));
                            }
                            TenantReportScheduleRecipient snapshot = copy(existing);
                            if (req.displayName() != null) existing.setDisplayName(req.displayName());
                            if (req.isActive() != null) existing.setIsActive(req.isActive());
                            existing.setUpdatedAt(OffsetDateTime.now());
                            existing.setActorId(parseUuid(actorId));
                            existing.setActorEmail(actorEmail);
                            return repository.save(existing)
                                    .flatMap(saved -> publishAudit(saved, snapshot, "UPDATE",
                                            actorId, actorEmail, schedule).thenReturn(saved));
                        }));
    }

    @Transactional
    public Mono<Void> delete(UUID tenantId, UUID scheduleId, UUID recipientId,
                             String actorId, String actorEmail) {
        return requireSchedule(tenantId, scheduleId)
                .flatMap(schedule -> repository.findById(recipientId)
                        .switchIfEmpty(Mono.error(new NoSuchElementException(
                                "Recipient not found: " + recipientId)))
                        .flatMap(existing -> {
                            if (!existing.getScheduleId().equals(schedule.getId())) {
                                return Mono.<TenantReportScheduleRecipient>error(
                                        new IllegalArgumentException(
                                                "Recipient does not belong to schedule"));
                            }
                            return repository.delete(existing)
                                    .then(publishAudit(existing, copy(existing), "DELETE",
                                            actorId, actorEmail, schedule))
                                    .thenReturn(existing);
                        }))
                .then();
    }

    /**
     * Public unsubscribe path — recipients click the delivery email link
     * which resolves this token, flips {@code isActive=false} and emits a
     * DELETE-shaped audit event with actor {@link AuditActor#SYSTEM_ID}
     * plus the reason for compliance context. The audit event's
     * {@code entityId} is the recipient row's UUID so audit UIs can still
     * link back.
     */
    @Transactional
    public Mono<TenantReportScheduleRecipient> unsubscribeByToken(UUID token, String reason) {
        return repository.findByUnsubscribeToken(token)
                .switchIfEmpty(Mono.error(new NoSuchElementException(
                        "No recipient for token " + token)))
                .flatMap(existing -> {
                    if (Boolean.FALSE.equals(existing.getIsActive())) {
                        // Idempotent — already unsubscribed. Return the row as-is
                        // without a second audit event.
                        return Mono.just(existing);
                    }
                    TenantReportScheduleRecipient snapshot = copy(existing);
                    existing.setIsActive(false);
                    existing.setUpdatedAt(OffsetDateTime.now());
                    existing.setActorEmail(UNSUBSCRIBE_ACTOR_EMAIL);
                    // Keep the acting UUID null — no human authenticated here.
                    existing.setActorId(null);
                    return repository.save(existing)
                            .flatMap(saved -> scheduleRepository.findById(saved.getScheduleId())
                                    .flatMap(schedule -> publishUnsubscribeAudit(
                                            saved, snapshot, reason, schedule))
                                    .thenReturn(saved));
                });
    }

    private Mono<TenantReportSchedule> requireSchedule(UUID tenantId, UUID scheduleId) {
        return scheduleRepository.findById(scheduleId)
                .filter(row -> row.getTenantId().equals(tenantId))
                .switchIfEmpty(Mono.error(new NoSuchElementException("Schedule not found: " + scheduleId)));
    }

    private static String normaliseEmail(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("email is required");
        return raw.trim().toLowerCase();
    }

    private TenantReportScheduleRecipient copy(TenantReportScheduleRecipient src) {
        TenantReportScheduleRecipient c = new TenantReportScheduleRecipient();
        c.setId(src.getId());
        c.setScheduleId(src.getScheduleId());
        c.setEmail(src.getEmail());
        c.setDisplayName(src.getDisplayName());
        c.setIsActive(src.getIsActive());
        c.setUnsubscribeToken(src.getUnsubscribeToken());
        c.setCreatedAt(src.getCreatedAt());
        c.setUpdatedAt(src.getUpdatedAt());
        c.setActorId(src.getActorId());
        c.setActorEmail(src.getActorEmail());
        return c;
    }

    private Mono<Void> publishAudit(TenantReportScheduleRecipient current,
                                    TenantReportScheduleRecipient previous,
                                    String action, String actorId, String actorEmail,
                                    TenantReportSchedule schedule) {
        Map<String, Object> oldMap = previous != null ? toMap(previous) : null;
        Map<String, Object> newMap = "DELETE".equals(action) ? null : toMap(current);
        String[] changed = "UPDATE".equals(action) && oldMap != null && newMap != null
                ? changedFields(oldMap, newMap) : null;
        return tenantRepository.findById(schedule.getTenantId())
                .map(Tenant::getSlug)
                .defaultIfEmpty("unknown")
                .flatMap(slug -> auditPublisher.publish(AuditEvent.create(
                        schedule.getTenantId().toString(),
                        ENTITY_TYPE,
                        current.getId().toString(),
                        String.format("Scheduled report recipient %s for %s (%s) - tenant %s",
                                current.getEmail(), schedule.getReportKey(),
                                schedule.getCadence(), slug),
                        action,
                        actorId,
                        actorEmail,
                        oldMap,
                        newMap,
                        changed,
                        UUID.randomUUID().toString())));
    }

    private Mono<Void> publishUnsubscribeAudit(TenantReportScheduleRecipient current,
                                               TenantReportScheduleRecipient previous,
                                               String reason,
                                               TenantReportSchedule schedule) {
        Map<String, Object> oldMap = toMap(previous);
        Map<String, Object> newMap = toMap(current);
        if (reason != null && !reason.isBlank()) {
            newMap.put("unsubscribeReason", reason);
        }
        String[] changed = changedFields(oldMap, newMap);
        return tenantRepository.findById(schedule.getTenantId())
                .map(Tenant::getSlug)
                .defaultIfEmpty("unknown")
                .flatMap(slug -> auditPublisher.publish(AuditEvent.create(
                        schedule.getTenantId().toString(),
                        ENTITY_TYPE,
                        current.getId().toString(),
                        String.format("Scheduled report recipient %s unsubscribed from %s (%s) - tenant %s",
                                current.getEmail(), schedule.getReportKey(),
                                schedule.getCadence(), slug),
                        "UNSUBSCRIBE",
                        UNSUBSCRIBE_ACTOR_ID,
                        UNSUBSCRIBE_ACTOR_EMAIL,
                        oldMap,
                        newMap,
                        changed,
                        UUID.randomUUID().toString())));
    }

    private static Map<String, Object> toMap(TenantReportScheduleRecipient row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("email", row.getEmail());
        m.put("displayName", row.getDisplayName());
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
