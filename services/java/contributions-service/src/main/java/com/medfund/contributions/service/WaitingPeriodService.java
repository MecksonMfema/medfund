package com.medfund.contributions.service;

import com.medfund.contributions.dto.UpsertSchemeChangeWaitingPeriodRequest;
import com.medfund.contributions.dto.UpsertWaitingPeriodRequest;
import com.medfund.contributions.entity.SchemeChangeWaitingPeriodRule;
import com.medfund.contributions.entity.WaitingPeriodRule;
import com.medfund.contributions.repository.SchemeChangeWaitingPeriodRuleRepository;
import com.medfund.contributions.repository.WaitingPeriodRuleRepository;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

/**
 * CRUD over the two waiting-period catalogues:
 * <ul>
 *   <li>{@code waiting_period_rules} (V001) — initial-enrolment per scheme.</li>
 *   <li>{@code scheme_change_waiting_period_rules} (V011) — applied on
 *       UPGRADE/DOWNGRADE between schemes.</li>
 * </ul>
 *
 * <p>Every mutation publishes an audit event via the existing
 * {@link AuditPublisher}, mirroring {@link BillingCatalogueService}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WaitingPeriodService {

    private final WaitingPeriodRuleRepository waitingRepo;
    private final SchemeChangeWaitingPeriodRuleRepository schemeChangeRepo;
    private final R2dbcEntityTemplate r2dbcTemplate;
    private final AuditPublisher auditPublisher;

    // ── Initial-enrolment waiting periods ────────────────────────────────────

    public Flux<WaitingPeriodRule> listAll() {
        return waitingRepo.findAllOrdered();
    }

    public Flux<WaitingPeriodRule> listByScheme(UUID schemeId) {
        return waitingRepo.findBySchemeId(schemeId);
    }

    @Transactional
    public Mono<WaitingPeriodRule> create(UpsertWaitingPeriodRequest req, String actorId, String actorEmail) {
        WaitingPeriodRule rule = new WaitingPeriodRule();
        rule.setSchemeId(req.schemeId());
        rule.setConditionType(req.conditionType());
        rule.setWaitingDays(req.waitingDays());
        rule.setDescription(req.description());
        rule.setCreatedAt(Instant.now());
        return r2dbcTemplate.insert(rule)
                .flatMap(saved -> publishAudit("WaitingPeriodRule", saved.getId(),
                        saved.getConditionType(), "CREATE", null, waitingMap(saved),
                        actorId, actorEmail).thenReturn(saved));
    }

    @Transactional
    public Mono<WaitingPeriodRule> update(UUID id, UpsertWaitingPeriodRequest req,
                                          String actorId, String actorEmail) {
        return waitingRepo.findById(id)
                .switchIfEmpty(Mono.error(new NoSuchElementException("Waiting period rule not found: " + id)))
                .flatMap(existing -> {
                    Map<String, Object> oldMap = waitingMap(existing);
                    existing.setSchemeId(req.schemeId());
                    existing.setConditionType(req.conditionType());
                    existing.setWaitingDays(req.waitingDays());
                    existing.setDescription(req.description());
                    return waitingRepo.save(existing)
                            .flatMap(saved -> publishAudit("WaitingPeriodRule", saved.getId(),
                                    saved.getConditionType(), "UPDATE", oldMap, waitingMap(saved),
                                    actorId, actorEmail).thenReturn(saved));
                });
    }

    @Transactional
    public Mono<Void> delete(UUID id, String actorId, String actorEmail) {
        return waitingRepo.findById(id)
                .switchIfEmpty(Mono.error(new NoSuchElementException("Waiting period rule not found: " + id)))
                .flatMap(existing -> waitingRepo.delete(existing)
                        .then(publishAudit("WaitingPeriodRule", existing.getId(),
                                existing.getConditionType(), "DELETE",
                                waitingMap(existing), null, actorId, actorEmail)));
    }

    // ── Scheme-change waiting periods ────────────────────────────────────────

    public Flux<SchemeChangeWaitingPeriodRule> listAllSchemeChange() {
        return schemeChangeRepo.findAllOrdered();
    }

    @Transactional
    public Mono<SchemeChangeWaitingPeriodRule> createSchemeChange(UpsertSchemeChangeWaitingPeriodRequest req,
                                                                   String actorId, String actorEmail) {
        SchemeChangeWaitingPeriodRule rule = new SchemeChangeWaitingPeriodRule();
        rule.setChangeType(req.changeType());
        rule.setBenefitType(req.benefitType());
        rule.setWaitingDays(req.waitingDays());
        rule.setDescription(req.description());
        rule.setIsActive(req.isActive() != null ? req.isActive() : true);
        rule.setCreatedAt(Instant.now());
        rule.setUpdatedAt(Instant.now());
        return r2dbcTemplate.insert(rule)
                .flatMap(saved -> publishAudit("SchemeChangeWaitingPeriodRule", saved.getId(),
                        saved.getChangeType(), "CREATE", null, schemeChangeMap(saved),
                        actorId, actorEmail).thenReturn(saved));
    }

    @Transactional
    public Mono<SchemeChangeWaitingPeriodRule> updateSchemeChange(UUID id, UpsertSchemeChangeWaitingPeriodRequest req,
                                                                   String actorId, String actorEmail) {
        return schemeChangeRepo.findById(id)
                .switchIfEmpty(Mono.error(new NoSuchElementException("Scheme-change waiting period not found: " + id)))
                .flatMap(existing -> {
                    Map<String, Object> oldMap = schemeChangeMap(existing);
                    existing.setChangeType(req.changeType());
                    existing.setBenefitType(req.benefitType());
                    existing.setWaitingDays(req.waitingDays());
                    existing.setDescription(req.description());
                    if (req.isActive() != null) existing.setIsActive(req.isActive());
                    existing.setUpdatedAt(Instant.now());
                    return schemeChangeRepo.save(existing)
                            .flatMap(saved -> publishAudit("SchemeChangeWaitingPeriodRule", saved.getId(),
                                    saved.getChangeType(), "UPDATE", oldMap, schemeChangeMap(saved),
                                    actorId, actorEmail).thenReturn(saved));
                });
    }

    @Transactional
    public Mono<Void> deleteSchemeChange(UUID id, String actorId, String actorEmail) {
        return schemeChangeRepo.findById(id)
                .switchIfEmpty(Mono.error(new NoSuchElementException("Scheme-change waiting period not found: " + id)))
                .flatMap(existing -> schemeChangeRepo.delete(existing)
                        .then(publishAudit("SchemeChangeWaitingPeriodRule", existing.getId(),
                                existing.getChangeType(), "DELETE",
                                schemeChangeMap(existing), null, actorId, actorEmail)));
    }

    // ── Audit helpers ────────────────────────────────────────────────────────

    private Mono<Void> publishAudit(String entityType, UUID entityId, String entityName,
                                    String action, Map<String, Object> oldMap, Map<String, Object> newMap,
                                    String actorId, String actorEmail) {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            String[] changedFields = "UPDATE".equals(action) && oldMap != null && newMap != null
                    ? changedFields(oldMap, newMap) : null;
            var event = AuditEvent.create(
                    tenantId != null ? tenantId : "unknown",
                    entityType,
                    entityId != null ? entityId.toString() : null,
                    entityName,
                    action,
                    actorId,
                    actorEmail,
                    oldMap,
                    newMap,
                    changedFields,
                    UUID.randomUUID().toString());
            return auditPublisher.publish(event);
        });
    }

    private Map<String, Object> waitingMap(WaitingPeriodRule r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schemeId", r.getSchemeId() != null ? r.getSchemeId().toString() : null);
        m.put("conditionType", r.getConditionType());
        m.put("waitingDays", r.getWaitingDays());
        m.put("description", r.getDescription());
        return m;
    }

    private Map<String, Object> schemeChangeMap(SchemeChangeWaitingPeriodRule r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("changeType", r.getChangeType());
        m.put("benefitType", r.getBenefitType());
        m.put("waitingDays", r.getWaitingDays());
        m.put("description", r.getDescription());
        m.put("isActive", r.getIsActive());
        return m;
    }

    private String[] changedFields(Map<String, Object> oldMap, Map<String, Object> newMap) {
        return newMap.keySet().stream()
                .filter(k -> !Objects.equals(oldMap.get(k), newMap.get(k)))
                .toArray(String[]::new);
    }
}
