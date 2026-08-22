package com.medfund.finance.reinsurance.service;

import com.medfund.finance.reinsurance.dto.TreatyLayerResponse;
import com.medfund.finance.reinsurance.dto.UpsertTreatyLayerRequest;
import com.medfund.finance.reinsurance.entity.TreatyLayer;
import com.medfund.finance.reinsurance.repository.TreatyLayerRepository;
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
 * Nested resource under {@link TreatyService} — layers only exist as
 * children of a treaty. All mutations require the parent treaty to be
 * in DRAFT (enforced by {@code TreatyService.requireDraft}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TreatyLayerService {

    private static final String ENTITY_TYPE = "TreatyLayer";

    private final TreatyLayerRepository repository;
    private final TreatyService treatyService;
    private final AuditPublisher auditPublisher;

    public Flux<TreatyLayerResponse> list(UUID treatyId) {
        return repository.findByTreatyIdOrderByLayerOrder(treatyId).map(TreatyLayerResponse::from);
    }

    @Transactional
    public Mono<TreatyLayerResponse> create(UUID treatyId, UpsertTreatyLayerRequest req,
                                            String actorId, String actorEmail) {
        return treatyService.requireDraft(treatyId).flatMap(treaty -> {
            TreatyLayer layer = new TreatyLayer();
            layer.setTreatyId(treatyId);
            apply(layer, req);
            layer.setCreatedAt(OffsetDateTime.now());
            return repository.save(layer)
                    .flatMap(saved -> publishAudit("CREATE", saved, treaty.getTreatyRef(),
                                    null, snapshot(saved), actorId, actorEmail)
                            .thenReturn(TreatyLayerResponse.from(saved)));
        });
    }

    @Transactional
    public Mono<TreatyLayerResponse> update(UUID treatyId, UUID layerId, UpsertTreatyLayerRequest req,
                                            String actorId, String actorEmail) {
        return treatyService.requireDraft(treatyId).flatMap(treaty -> repository.findById(layerId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Layer not found: " + layerId)))
                .flatMap(existing -> {
                    if (!existing.getTreatyId().equals(treatyId)) {
                        return Mono.error(new IllegalArgumentException(
                                "Layer " + layerId + " does not belong to treaty " + treatyId));
                    }
                    Map<String, Object> before = snapshot(existing);
                    apply(existing, req);
                    return repository.save(existing)
                            .flatMap(saved -> publishAudit("UPDATE", saved, treaty.getTreatyRef(),
                                            before, snapshot(saved), actorId, actorEmail)
                                    .thenReturn(TreatyLayerResponse.from(saved)));
                }));
    }

    @Transactional
    public Mono<Void> delete(UUID treatyId, UUID layerId, String actorId, String actorEmail) {
        return treatyService.requireDraft(treatyId).flatMap(treaty -> repository.findById(layerId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Layer not found: " + layerId)))
                .flatMap(existing -> {
                    if (!existing.getTreatyId().equals(treatyId)) {
                        return Mono.error(new IllegalArgumentException(
                                "Layer " + layerId + " does not belong to treaty " + treatyId));
                    }
                    Map<String, Object> before = snapshot(existing);
                    return repository.deleteById(layerId)
                            .then(publishAudit("DELETE", existing, treaty.getTreatyRef(),
                                    before, null, actorId, actorEmail));
                }));
    }

    private void apply(TreatyLayer layer, UpsertTreatyLayerRequest req) {
        layer.setLayerOrder(req.layerOrder());
        layer.setRetention(req.retention());
        layer.setLayerLimit(req.layerLimit());
        layer.setLayerCurrency(req.layerCurrency());
        layer.setRate(req.rate());
        layer.setReinstatementCount(req.reinstatementCount());
    }

    private Map<String, Object> snapshot(TreatyLayer l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("treatyId",           l.getTreatyId());
        m.put("layerOrder",         l.getLayerOrder());
        m.put("retention",          l.getRetention());
        m.put("layerLimit",         l.getLayerLimit());
        m.put("layerCurrency",      l.getLayerCurrency());
        m.put("rate",               l.getRate());
        m.put("reinstatementCount", l.getReinstatementCount());
        return m;
    }

    private String[] diff(Map<String, Object> before, Map<String, Object> after) {
        if (before == null || after == null) return new String[0];
        return before.keySet().stream()
                .filter(k -> !Objects.equals(before.get(k), after.get(k)))
                .toArray(String[]::new);
    }

    private Mono<Void> publishAudit(String action, TreatyLayer entity, String treatyRef,
                                    Map<String, Object> before, Map<String, Object> after,
                                    String actorId, String actorEmail) {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            var event = AuditEvent.create(
                    tenantId != null ? tenantId : "unknown",
                    ENTITY_TYPE,
                    entity.getId().toString(),
                    "Layer " + entity.getLayerOrder() + " on treaty " + treatyRef,
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
