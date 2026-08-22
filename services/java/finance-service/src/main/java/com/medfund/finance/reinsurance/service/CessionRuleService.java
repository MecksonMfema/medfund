package com.medfund.finance.reinsurance.service;

import com.medfund.finance.reinsurance.dto.CessionRuleResponse;
import com.medfund.finance.reinsurance.dto.CreateCessionRuleRequest;
import com.medfund.finance.reinsurance.dto.UpdateCessionRuleRequest;
import com.medfund.finance.reinsurance.entity.CessionRule;
import com.medfund.finance.reinsurance.repository.CessionRuleRepository;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Link rows between a treaty and the rules-engine rules that drive its
 * cession behavior. The rule authoring itself lives in the shared rules
 * builder — this service just tracks the (treaty, rule) association.
 * Enabled/disabled is toggleable on ACTIVE treaties; add/remove requires
 * DRAFT.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CessionRuleService {

    private static final String ENTITY_TYPE = "CessionRule";

    private final CessionRuleRepository repository;
    private final TreatyService treatyService;
    private final AuditPublisher auditPublisher;

    public Flux<CessionRuleResponse> list(UUID treatyId) {
        return repository.findByTreatyId(treatyId).map(CessionRuleResponse::from);
    }

    @Transactional
    public Mono<CessionRuleResponse> add(UUID treatyId, CreateCessionRuleRequest req,
                                         String actorId, String actorEmail) {
        return treatyService.requireDraft(treatyId).flatMap(treaty ->
                repository.findByTreatyIdAndRuleDefinitionId(treatyId, req.ruleDefinitionId())
                        .flatMap(existing -> Mono.<CessionRuleResponse>error(new IllegalStateException(
                                "Rule already linked to treaty (id=" + existing.getId() + ")")))
                        .switchIfEmpty(Mono.defer(() -> {
                            CessionRule fresh = new CessionRule();
                            fresh.setTreatyId(treatyId);
                            fresh.setRuleDefinitionId(req.ruleDefinitionId());
                            fresh.setEnabled(req.enabled() == null ? Boolean.TRUE : req.enabled());
                            OffsetDateTime now = OffsetDateTime.now();
                            fresh.setCreatedAt(now);
                            fresh.setUpdatedAt(now);
                            fresh.setActorId(parseUuid(actorId));
                            fresh.setActorEmail(actorEmail);
                            return repository.save(fresh)
                                    .flatMap(saved -> publishAudit("CREATE", saved, treaty.getTreatyRef(),
                                                    null, snapshot(saved), actorId, actorEmail)
                                            .thenReturn(CessionRuleResponse.from(saved)));
                        })));
    }

    @Transactional
    public Mono<CessionRuleResponse> updateEnabled(UUID treatyId, UUID ruleLinkId, UpdateCessionRuleRequest req,
                                                   String actorId, String actorEmail) {
        return repository.findById(ruleLinkId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Cession rule link not found: " + ruleLinkId)))
                .flatMap(existing -> {
                    if (!existing.getTreatyId().equals(treatyId)) {
                        return Mono.error(new IllegalArgumentException(
                                "Rule link " + ruleLinkId + " does not belong to treaty " + treatyId));
                    }
                    Map<String, Object> before = snapshot(existing);
                    existing.setEnabled(req.enabled());
                    existing.setUpdatedAt(OffsetDateTime.now());
                    existing.setActorId(parseUuid(actorId));
                    existing.setActorEmail(actorEmail);
                    return repository.save(existing)
                            .flatMap(saved -> publishAudit("UPDATE", saved, "treaty",
                                            before, snapshot(saved), actorId, actorEmail)
                                    .thenReturn(CessionRuleResponse.from(saved)));
                });
    }

    @Transactional
    public Mono<Void> delete(UUID treatyId, UUID ruleLinkId, String actorId, String actorEmail) {
        return treatyService.requireDraft(treatyId).flatMap(treaty -> repository.findById(ruleLinkId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Cession rule link not found: " + ruleLinkId)))
                .flatMap(existing -> {
                    if (!existing.getTreatyId().equals(treatyId)) {
                        return Mono.error(new IllegalArgumentException(
                                "Rule link " + ruleLinkId + " does not belong to treaty " + treatyId));
                    }
                    Map<String, Object> before = snapshot(existing);
                    return repository.deleteById(ruleLinkId)
                            .then(publishAudit("DELETE", existing, treaty.getTreatyRef(),
                                    before, null, actorId, actorEmail));
                }));
    }

    private Map<String, Object> snapshot(CessionRule r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("treatyId",         r.getTreatyId());
        m.put("ruleDefinitionId", r.getRuleDefinitionId());
        m.put("enabled",          r.getEnabled());
        return m;
    }

    private String[] diff(Map<String, Object> before, Map<String, Object> after) {
        if (before == null || after == null) return new String[0];
        return before.keySet().stream()
                .filter(k -> !Objects.equals(before.get(k), after.get(k)))
                .toArray(String[]::new);
    }

    private Mono<Void> publishAudit(String action, CessionRule entity, String treatyRef,
                                    Map<String, Object> before, Map<String, Object> after,
                                    String actorId, String actorEmail) {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            var event = AuditEvent.create(
                    tenantId != null ? tenantId : "unknown",
                    ENTITY_TYPE,
                    entity.getId().toString(),
                    "Cession rule on treaty " + treatyRef,
                    action,
                    actorId != null ? actorId : "system",
                    actorEmail,
                    before,
                    after,
                    diff(before, after),
                    UUID.randomUUID().toString());
            return auditPublisher.publish(event);
        });
    }

    private UUID parseUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
    }
}
