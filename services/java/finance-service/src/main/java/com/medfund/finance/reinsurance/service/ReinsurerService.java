package com.medfund.finance.reinsurance.service;

import com.medfund.finance.reinsurance.dto.CreateReinsurerRequest;
import com.medfund.finance.reinsurance.dto.ReinsurerResponse;
import com.medfund.finance.reinsurance.dto.UpdateReinsurerRequest;
import com.medfund.finance.reinsurance.entity.Reinsurer;
import com.medfund.finance.reinsurance.repository.ReinsurerRepository;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.finance.dto.PageResponse;
import com.medfund.shared.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * CRUD for the reinsurer counterparty master. Deactivation is a soft flip
 * on {@code is_active}; the partial unique index on {@code name} where
 * {@code is_active = TRUE} allows a fresh row with the same display name
 * to be created once the old one is deactivated.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReinsurerService {

    private static final String ENTITY_TYPE = "Reinsurer";

    private final ReinsurerRepository repository;
    private final AuditPublisher auditPublisher;

    public Mono<PageResponse<ReinsurerResponse>> list(int page, int size, Boolean active) {
        int offset = page * size;
        var contentFlux = (active != null
                ? repository.findPageByActive(active, offset, size)
                : repository.findPage(offset, size))
                .map(ReinsurerResponse::from)
                .collectList();
        var countMono = (active != null ? repository.countByActive(active) : repository.countAll());
        return contentFlux.zipWith(countMono, (rows, total) -> PageResponse.of(rows, total, page, size));
    }

    public Mono<ReinsurerResponse> get(UUID id) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Reinsurer not found: " + id)))
                .map(ReinsurerResponse::from);
    }

    @Transactional
    public Mono<ReinsurerResponse> create(CreateReinsurerRequest req, String actorId, String actorEmail) {
        Reinsurer r = new Reinsurer();
        r.setName(req.name());
        r.setContactEmail(req.contactEmail());
        r.setContactAddress(req.contactAddress());
        r.setJurisdictionCode(req.jurisdictionCode());
        r.setHomeCurrency(req.homeCurrency());
        r.setCreditRating(req.creditRating());
        r.setActive(true);
        r.setCreatedAt(OffsetDateTime.now());
        r.setUpdatedAt(r.getCreatedAt());
        r.setActorId(parseUuid(actorId));
        r.setActorEmail(actorEmail);
        return repository.save(r)
                .flatMap(saved -> publishAudit("CREATE", saved, null, snapshot(saved), actorId, actorEmail)
                        .thenReturn(ReinsurerResponse.from(saved)));
    }

    @Transactional
    public Mono<ReinsurerResponse> update(UUID id, UpdateReinsurerRequest req, String actorId, String actorEmail) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Reinsurer not found: " + id)))
                .flatMap(existing -> {
                    Map<String, Object> before = snapshot(existing);
                    existing.setName(req.name());
                    existing.setContactEmail(req.contactEmail());
                    existing.setContactAddress(req.contactAddress());
                    existing.setJurisdictionCode(req.jurisdictionCode());
                    existing.setHomeCurrency(req.homeCurrency());
                    existing.setCreditRating(req.creditRating());
                    existing.setActive(req.active());
                    existing.setUpdatedAt(OffsetDateTime.now());
                    existing.setActorId(parseUuid(actorId));
                    existing.setActorEmail(actorEmail);
                    return repository.save(existing)
                            .flatMap(saved -> publishAudit("UPDATE", saved, before, snapshot(saved), actorId, actorEmail)
                                    .thenReturn(ReinsurerResponse.from(saved)));
                });
    }

    private Map<String, Object> snapshot(Reinsurer r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name",             r.getName());
        m.put("contactEmail",     r.getContactEmail());
        m.put("contactAddress",   r.getContactAddress());
        m.put("jurisdictionCode", r.getJurisdictionCode());
        m.put("homeCurrency",     r.getHomeCurrency());
        m.put("creditRating",     r.getCreditRating());
        m.put("active",           r.getActive());
        return m;
    }

    private String[] diff(Map<String, Object> before, Map<String, Object> after) {
        if (before == null || after == null) return new String[0];
        return before.keySet().stream()
                .filter(k -> !Objects.equals(before.get(k), after.get(k)))
                .toArray(String[]::new);
    }

    private Mono<Void> publishAudit(String action, Reinsurer entity,
                                    Map<String, Object> before, Map<String, Object> after,
                                    String actorId, String actorEmail) {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            var event = AuditEvent.create(
                    tenantId != null ? tenantId : "unknown",
                    ENTITY_TYPE,
                    entity.getId().toString(),
                    entity.getName(),
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
