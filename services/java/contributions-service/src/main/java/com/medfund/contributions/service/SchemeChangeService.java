package com.medfund.contributions.service;

import com.medfund.contributions.dto.SchemeChangeRequest;
import com.medfund.contributions.entity.SchemeChange;
import com.medfund.contributions.repository.SchemeChangeRepository;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

@Service
public class SchemeChangeService {

    private static final Logger log = LoggerFactory.getLogger(SchemeChangeService.class);

    private final SchemeChangeRepository schemeChangeRepository;
    private final AuditPublisher auditPublisher;
    private final ContributionEventPublisher eventPublisher;

    public SchemeChangeService(SchemeChangeRepository schemeChangeRepository,
                               AuditPublisher auditPublisher,
                               ContributionEventPublisher eventPublisher) {
        this.schemeChangeRepository = schemeChangeRepository;
        this.auditPublisher = auditPublisher;
        this.eventPublisher = eventPublisher;
    }

    public Flux<SchemeChange> findByMemberId(UUID memberId) {
        return schemeChangeRepository.findByMemberId(memberId);
    }

    public Flux<SchemeChange> findPending() {
        return schemeChangeRepository.findByStatus("PENDING");
    }

    @Transactional
    public Mono<SchemeChange> request(SchemeChangeRequest request, String actorId, String actorEmail) {
        var schemeChange = new SchemeChange();
        schemeChange.setMemberId(request.memberId());
        schemeChange.setFromSchemeId(request.fromSchemeId());
        schemeChange.setToSchemeId(request.toSchemeId());
        schemeChange.setStatus("PENDING");
        schemeChange.setRequestedDate(LocalDate.now());
        schemeChange.setEffectiveDate(request.effectiveDate());
        schemeChange.setReason(request.reason());
        schemeChange.setCreatedAt(Instant.now());
        schemeChange.setUpdatedAt(Instant.now());
        schemeChange.setCreatedBy(UUID.fromString(actorId));

        return schemeChangeRepository.save(schemeChange)
            .flatMap(saved -> Mono.deferContextual(ctx -> {
                String tenantId = TenantContext.get(ctx);
                return publishAudit(tenantId, "SchemeChange", saved.getId().toString(), schemeChangeName(saved),
                        "CREATE", actorId, actorEmail,
                        null,
                        Map.of("memberId", saved.getMemberId().toString(),
                               "fromSchemeId", saved.getFromSchemeId().toString(),
                               "toSchemeId", saved.getToSchemeId().toString(),
                               "status", saved.getStatus(),
                               "effectiveDate", saved.getEffectiveDate().toString()))
                    .thenReturn(saved);
            }));
    }

    @Transactional
    public Mono<SchemeChange> approve(UUID id, String actorId, String actorEmail) {
        return schemeChangeRepository.findById(id)
            .switchIfEmpty(Mono.error(new IllegalArgumentException("Scheme change not found: " + id)))
            .flatMap(sc -> {
                if (!"PENDING".equals(sc.getStatus())) {
                    return Mono.error(new IllegalStateException(
                        "Scheme change " + id + " is not PENDING, current status: " + sc.getStatus()));
                }

                String previousStatus = sc.getStatus();
                sc.setStatus("APPROVED");
                sc.setApprovedBy(UUID.fromString(actorId));
                sc.setApprovedAt(Instant.now());
                sc.setUpdatedAt(Instant.now());

                return schemeChangeRepository.save(sc)
                    .flatMap(saved -> Mono.deferContextual(ctx -> {
                        String tenantId = TenantContext.get(ctx);
                        return publishAudit(tenantId, "SchemeChange", saved.getId().toString(), schemeChangeName(saved),
                                "UPDATE", actorId, actorEmail,
                                Map.of("status", previousStatus),
                                Map.of("status", saved.getStatus(),
                                       "approvedBy", actorId))
                            .thenReturn(saved);
                    }));
            });
    }

    @Transactional
    public Mono<SchemeChange> reject(UUID id, String reason, String actorId, String actorEmail) {
        return schemeChangeRepository.findById(id)
            .switchIfEmpty(Mono.error(new IllegalArgumentException("Scheme change not found: " + id)))
            .flatMap(sc -> {
                if (!"PENDING".equals(sc.getStatus())) {
                    return Mono.error(new IllegalStateException(
                        "Scheme change " + id + " is not PENDING, current status: " + sc.getStatus()));
                }

                String previousStatus = sc.getStatus();
                sc.setStatus("REJECTED");
                sc.setRejectionReason(reason);
                sc.setUpdatedAt(Instant.now());

                return schemeChangeRepository.save(sc)
                    .flatMap(saved -> Mono.deferContextual(ctx -> {
                        String tenantId = TenantContext.get(ctx);
                        return publishAudit(tenantId, "SchemeChange", saved.getId().toString(), schemeChangeName(saved),
                                "UPDATE", actorId, actorEmail,
                                Map.of("status", previousStatus),
                                Map.of("status", saved.getStatus(),
                                       "rejectionReason", reason))
                            .thenReturn(saved);
                    }));
            });
    }

    @Transactional
    public Mono<SchemeChange> makeEffective(UUID id, String actorId, String actorEmail) {
        return schemeChangeRepository.findById(id)
            .switchIfEmpty(Mono.error(new IllegalArgumentException("Scheme change not found: " + id)))
            .flatMap(sc -> {
                if (!"APPROVED".equals(sc.getStatus())) {
                    return Mono.error(new IllegalStateException(
                        "Scheme change " + id + " is not APPROVED, current status: " + sc.getStatus()));
                }

                String previousStatus = sc.getStatus();
                sc.setStatus("EFFECTIVE");
                sc.setUpdatedAt(Instant.now());

                return schemeChangeRepository.save(sc)
                    .flatMap(saved -> Mono.deferContextual(ctx -> {
                        String tenantId = TenantContext.get(ctx);
                        return publishAudit(tenantId, "SchemeChange", saved.getId().toString(), schemeChangeName(saved),
                                "UPDATE", actorId, actorEmail,
                                Map.of("status", previousStatus),
                                Map.of("status", saved.getStatus()))
                            // Fire a domain event so SchemeChangedConsumer can
                            // auto-post arrears / rebate when the effective
                            // date overlaps an already-billed period.
                            // Fire-and-forget — Kafka failure never rolls
                            // back the persisted status flip.
                            .then(eventPublisher.publishSchemeChanged(
                                new ContributionEventPublisher.SchemeChangedPayload(
                                    saved.getId().toString(),
                                    saved.getMemberId() != null ? saved.getMemberId().toString() : null,
                                    tenantId,
                                    saved.getFromSchemeId() != null ? saved.getFromSchemeId().toString() : null,
                                    saved.getToSchemeId()   != null ? saved.getToSchemeId().toString()   : null,
                                    saved.getEffectiveDate() != null ? saved.getEffectiveDate().toString() : null)))
                            .thenReturn(saved);
                    }));
            });
    }

    // ---- Private helpers ----

    private Mono<Void> publishAudit(String tenantId, String entityType, String entityId, String entityName,
                                     String action, String actorId, String actorEmail,
                                     Map<String, Object> oldValue, Map<String, Object> newValue) {
        var event = AuditEvent.create(
            tenantId != null ? tenantId : "unknown",
            entityType,
            entityId,
            entityName,
            action,
            actorId,
            actorEmail,
            oldValue,
            newValue,
            new String[]{"status"},
            UUID.randomUUID().toString()
        );
        return auditPublisher.publish(event);
    }

    /**
     * SchemeChange has no business identifier of its own — synthesize a label
     * from the member and from→to scheme IDs so the audit_events row carries
     * something meaningful instead of duplicating the UUID.
     */
    private static String schemeChangeName(SchemeChange sc) {
        String member = sc.getMemberId() != null ? sc.getMemberId().toString() : "?";
        String from = sc.getFromSchemeId() != null ? sc.getFromSchemeId().toString() : "?";
        String to = sc.getToSchemeId() != null ? sc.getToSchemeId().toString() : "?";
        return "member " + member + " " + from + " -> " + to;
    }
}
