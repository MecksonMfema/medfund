package com.medfund.finance.reinsurance.service;

import com.medfund.finance.reinsurance.dto.TreatyParticipantResponse;
import com.medfund.finance.reinsurance.dto.UpsertTreatyParticipantRequest;
import com.medfund.finance.reinsurance.entity.Reinsurer;
import com.medfund.finance.reinsurance.entity.TreatyParticipant;
import com.medfund.finance.reinsurance.repository.ReinsurerRepository;
import com.medfund.finance.reinsurance.repository.TreatyParticipantRepository;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * CRUD for the (treaty, reinsurer) association. Composite key means the
 * upsert path is: existing → UPDATE, absent → INSERT. Only editable while
 * the parent treaty is DRAFT.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TreatyParticipantService {

    private static final String ENTITY_TYPE = "TreatyParticipant";

    private final TreatyParticipantRepository repository;
    private final ReinsurerRepository reinsurerRepository;
    private final TreatyService treatyService;
    private final AuditPublisher auditPublisher;

    public Flux<TreatyParticipantResponse> list(UUID treatyId) {
        return repository.findByTreatyId(treatyId)
                .flatMap(p -> reinsurerRepository.findById(p.getReinsurerId())
                        .map(Reinsurer::getName)
                        .defaultIfEmpty("(unknown)")
                        .map(name -> TreatyParticipantResponse.from(p, name)));
    }

    @Transactional
    public Mono<TreatyParticipantResponse> upsert(UUID treatyId, UpsertTreatyParticipantRequest req,
                                                  String actorId, String actorEmail) {
        return treatyService.requireDraft(treatyId)
                .then(reinsurerRepository.findById(req.reinsurerId())
                        .switchIfEmpty(Mono.error(new IllegalArgumentException(
                                "Reinsurer not found: " + req.reinsurerId()))))
                .flatMap(reinsurer -> repository.findByTreatyIdAndReinsurerId(treatyId, req.reinsurerId())
                        .flatMap(existing -> {
                            Map<String, Object> before = snapshot(existing);
                            existing.setSharePct(req.sharePct());
                            existing.setShareRole(req.shareRole());
                            return repository.update(existing)
                                    .flatMap(saved -> publishAudit("UPDATE", saved, reinsurer.getName(),
                                                    before, snapshot(saved), actorId, actorEmail)
                                            .thenReturn(TreatyParticipantResponse.from(saved, reinsurer.getName())));
                        })
                        .switchIfEmpty(Mono.defer(() -> {
                            TreatyParticipant fresh = new TreatyParticipant();
                            fresh.setTreatyId(treatyId);
                            fresh.setReinsurerId(req.reinsurerId());
                            fresh.setSharePct(req.sharePct());
                            fresh.setShareRole(req.shareRole());
                            return repository.insert(fresh)
                                    .flatMap(saved -> publishAudit("CREATE", saved, reinsurer.getName(),
                                                    null, snapshot(saved), actorId, actorEmail)
                                            .thenReturn(TreatyParticipantResponse.from(saved, reinsurer.getName())));
                        })));
    }

    @Transactional
    public Mono<Void> delete(UUID treatyId, UUID reinsurerId, String actorId, String actorEmail) {
        return treatyService.requireDraft(treatyId)
                .then(repository.findByTreatyIdAndReinsurerId(treatyId, reinsurerId)
                        .switchIfEmpty(Mono.error(new IllegalArgumentException("Participant not found"))))
                .flatMap(existing -> reinsurerRepository.findById(reinsurerId)
                        .map(Reinsurer::getName)
                        .defaultIfEmpty("(unknown)")
                        .flatMap(name -> {
                            Map<String, Object> before = snapshot(existing);
                            return repository.delete(treatyId, reinsurerId)
                                    .then(publishAudit("DELETE", existing, name,
                                            before, null, actorId, actorEmail));
                        }));
    }

    private Map<String, Object> snapshot(TreatyParticipant p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("treatyId",    p.getTreatyId());
        m.put("reinsurerId", p.getReinsurerId());
        m.put("sharePct",    p.getSharePct());
        m.put("shareRole",   p.getShareRole());
        return m;
    }

    private String[] diff(Map<String, Object> before, Map<String, Object> after) {
        if (before == null || after == null) return new String[0];
        return before.keySet().stream()
                .filter(k -> !Objects.equals(before.get(k), after.get(k)))
                .toArray(String[]::new);
    }

    private Mono<Void> publishAudit(String action, TreatyParticipant entity, String reinsurerName,
                                    Map<String, Object> before, Map<String, Object> after,
                                    String actorId, String actorEmail) {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            var event = AuditEvent.create(
                    tenantId != null ? tenantId : "unknown",
                    ENTITY_TYPE,
                    entity.getTreatyId() + ":" + entity.getReinsurerId(),
                    reinsurerName + " participation",
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
}
