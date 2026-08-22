package com.medfund.finance.reinsurance.service;

import com.medfund.finance.dto.PageResponse;
import com.medfund.finance.reinsurance.dto.CreateTreatyRequest;
import com.medfund.finance.reinsurance.dto.RenewTreatyRequest;
import com.medfund.finance.reinsurance.dto.TreatyResponse;
import com.medfund.finance.reinsurance.dto.UpdateTreatyRequest;
import com.medfund.finance.reinsurance.entity.Treaty;
import com.medfund.finance.reinsurance.repository.TreatyRepository;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Treaty lifecycle. DRAFT is the authoring surface — all edits happen
 * here. ACTIVE is the operational state — the plan mandates 409 on
 * edit attempts; the correction path is void + re-create. Terminal
 * states (EXPIRED / RENEWED / LAPSED / COMMUTED) are set either by
 * successor-activation (RENEWED), operator (LAPSED), or a future
 * scheduler once expiry policy lands.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TreatyService {

    private static final String ENTITY_TYPE = "Treaty";

    private final TreatyRepository repository;
    private final TreatyValidationService validationService;
    private final AuditPublisher auditPublisher;

    public Mono<PageResponse<TreatyResponse>> list(int page, int size, String status) {
        int offset = page * size;
        var content = (status != null
                ? repository.findPageByStatus(status, offset, size)
                : repository.findPage(offset, size))
                .map(TreatyResponse::from)
                .collectList();
        var count = (status != null ? repository.countByStatus(status) : repository.countAll());
        return content.zipWith(count, (rows, total) -> PageResponse.of(rows, total, page, size));
    }

    public Mono<TreatyResponse> get(UUID id) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Treaty not found: " + id)))
                .map(TreatyResponse::from);
    }

    @Transactional
    public Mono<TreatyResponse> createDraft(CreateTreatyRequest req, String actorId, String actorEmail) {
        if (!req.expiryDate().isAfter(req.inceptionDate())) {
            return Mono.error(new IllegalArgumentException("expiryDate must be after inceptionDate"));
        }
        Treaty t = new Treaty();
        applyCreate(t, req);
        t.setStatus("DRAFT");
        OffsetDateTime now = OffsetDateTime.now();
        t.setCreatedAt(now);
        t.setUpdatedAt(now);
        t.setActorId(parseUuid(actorId));
        t.setActorEmail(actorEmail);
        return repository.save(t)
                .flatMap(saved -> publishAudit("CREATE", saved, null, snapshot(saved), actorId, actorEmail)
                        .thenReturn(TreatyResponse.from(saved)));
    }

    @Transactional
    public Mono<TreatyResponse> update(UUID id, UpdateTreatyRequest req, String actorId, String actorEmail) {
        if (!req.expiryDate().isAfter(req.inceptionDate())) {
            return Mono.error(new IllegalArgumentException("expiryDate must be after inceptionDate"));
        }
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Treaty not found: " + id)))
                .flatMap(existing -> {
                    if (!"DRAFT".equals(existing.getStatus())) {
                        return Mono.error(new IllegalStateException(
                                "Treaty is " + existing.getStatus() + " — only DRAFT treaties are editable. "
                                        + "Void this treaty and create a new one instead."));
                    }
                    Map<String, Object> before = snapshot(existing);
                    applyUpdate(existing, req);
                    existing.setUpdatedAt(OffsetDateTime.now());
                    existing.setActorId(parseUuid(actorId));
                    existing.setActorEmail(actorEmail);
                    return repository.save(existing)
                            .flatMap(saved -> publishAudit("UPDATE", saved, before, snapshot(saved), actorId, actorEmail)
                                    .thenReturn(TreatyResponse.from(saved)));
                });
    }

    @Transactional
    public Mono<TreatyResponse> activate(UUID id, String actorId, String actorEmail) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Treaty not found: " + id)))
                .flatMap(existing -> {
                    if (!"DRAFT".equals(existing.getStatus())) {
                        return Mono.error(new IllegalStateException(
                                "Treaty must be DRAFT to activate — was " + existing.getStatus()));
                    }
                    return validationService.validateForActivation(existing)
                            .then(Mono.defer(() -> {
                                Map<String, Object> before = snapshot(existing);
                                OffsetDateTime now = OffsetDateTime.now();
                                existing.setStatus("ACTIVE");
                                existing.setActivatedAt(now);
                                existing.setUpdatedAt(now);
                                existing.setActorId(parseUuid(actorId));
                                existing.setActorEmail(actorEmail);
                                return repository.save(existing)
                                        .flatMap(saved -> publishAudit("ACTIVATE", saved, before,
                                                        snapshot(saved), actorId, actorEmail)
                                                .thenReturn(TreatyResponse.from(saved)));
                            }));
                });
    }

    @Transactional
    public Mono<TreatyResponse> voidDraft(UUID id, String actorId, String actorEmail) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Treaty not found: " + id)))
                .flatMap(existing -> {
                    if (!"DRAFT".equals(existing.getStatus())) {
                        return Mono.error(new IllegalStateException(
                                "Only DRAFT treaties can be voided (was " + existing.getStatus()
                                        + "). ACTIVE treaties are commuted, not voided."));
                    }
                    Map<String, Object> before = snapshot(existing);
                    existing.setStatus("LAPSED");
                    existing.setUpdatedAt(OffsetDateTime.now());
                    existing.setActorId(parseUuid(actorId));
                    existing.setActorEmail(actorEmail);
                    return repository.save(existing)
                            .flatMap(saved -> publishAudit("VOID", saved, before, snapshot(saved), actorId, actorEmail)
                                    .thenReturn(TreatyResponse.from(saved)));
                });
    }

    @Transactional
    public Mono<TreatyResponse> renew(UUID priorId, RenewTreatyRequest req, String actorId, String actorEmail) {
        if (!req.expiryDate().isAfter(req.inceptionDate())) {
            return Mono.error(new IllegalArgumentException("expiryDate must be after inceptionDate"));
        }
        return repository.findById(priorId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Prior treaty not found: " + priorId)))
                .flatMap(prior -> {
                    if (!"ACTIVE".equals(prior.getStatus()) && !"EXPIRED".equals(prior.getStatus())) {
                        return Mono.error(new IllegalStateException(
                                "Can only renew ACTIVE or EXPIRED treaties (was " + prior.getStatus() + ")"));
                    }
                    Treaty successor = new Treaty();
                    successor.setTreatyRef(req.treatyRef());
                    successor.setTreatyType(prior.getTreatyType());
                    successor.setDeclaredCurrency(prior.getDeclaredCurrency());
                    successor.setInceptionDate(req.inceptionDate());
                    successor.setExpiryDate(req.expiryDate());
                    successor.setStatus("DRAFT");
                    successor.setRenewedFromTreatyId(prior.getId());
                    successor.setAggregateLimit(req.aggregateLimit());
                    successor.setAggregateLimitCurrency(req.aggregateLimitCurrency());
                    successor.setExpectedAnnualPremium(req.expectedAnnualPremium());
                    successor.setProducerRef(prior.getProducerRef());
                    OffsetDateTime now = OffsetDateTime.now();
                    successor.setCreatedAt(now);
                    successor.setUpdatedAt(now);
                    successor.setActorId(parseUuid(actorId));
                    successor.setActorEmail(actorEmail);
                    return repository.save(successor)
                            .flatMap(saved -> publishAudit("RENEW", saved, null, snapshot(saved), actorId, actorEmail)
                                    .thenReturn(TreatyResponse.from(saved)));
                });
    }

    /**
     * Walk {@code renewed_from_treaty_id} back to the root; return root-first
     * ordered list. Bounded loop guard: 50 hops — deeper chains are anomalous
     * and would rather surface than spin.
     */
    public Mono<List<TreatyResponse>> renewalChain(UUID leafId) {
        List<Treaty> collected = new ArrayList<>();
        return walkChain(leafId, collected, 50)
                .then(Mono.fromCallable(() -> {
                    Collections.reverse(collected);
                    return collected.stream().map(TreatyResponse::from).toList();
                }));
    }

    private Mono<Void> walkChain(UUID id, List<Treaty> acc, int hopsLeft) {
        if (id == null || hopsLeft <= 0) return Mono.empty();
        return repository.findById(id)
                .flatMap(t -> {
                    acc.add(t);
                    return t.getRenewedFromTreatyId() != null
                            ? walkChain(t.getRenewedFromTreatyId(), acc, hopsLeft - 1)
                            : Mono.empty();
                });
    }

    /** Repositories used by nested-resource services to enforce the DRAFT-only-editable rule. */
    public Mono<Treaty> requireDraft(UUID treatyId) {
        return repository.findById(treatyId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Treaty not found: " + treatyId)))
                .flatMap(t -> "DRAFT".equals(t.getStatus())
                        ? Mono.just(t)
                        : Mono.error(new IllegalStateException(
                                "Treaty is " + t.getStatus() + " — nested resources are editable only while DRAFT.")));
    }

    public Flux<Treaty> allByStatus(String status) {
        return repository.findPageByStatus(status, 0, Integer.MAX_VALUE);
    }

    private void applyCreate(Treaty t, CreateTreatyRequest req) {
        t.setTreatyRef(req.treatyRef());
        t.setTreatyType(req.treatyType());
        t.setDeclaredCurrency(req.declaredCurrency());
        t.setInceptionDate(req.inceptionDate());
        t.setExpiryDate(req.expiryDate());
        t.setAggregateLimit(req.aggregateLimit());
        t.setAggregateLimitCurrency(req.aggregateLimitCurrency());
        t.setExpectedAnnualPremium(req.expectedAnnualPremium());
        t.setProducerRef(req.producerRef());
    }

    private void applyUpdate(Treaty t, UpdateTreatyRequest req) {
        t.setTreatyRef(req.treatyRef());
        t.setTreatyType(req.treatyType());
        t.setDeclaredCurrency(req.declaredCurrency());
        t.setInceptionDate(req.inceptionDate());
        t.setExpiryDate(req.expiryDate());
        t.setAggregateLimit(req.aggregateLimit());
        t.setAggregateLimitCurrency(req.aggregateLimitCurrency());
        t.setExpectedAnnualPremium(req.expectedAnnualPremium());
        t.setProducerRef(req.producerRef());
    }

    private Map<String, Object> snapshot(Treaty t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("treatyRef",             t.getTreatyRef());
        m.put("treatyType",            t.getTreatyType());
        m.put("declaredCurrency",      t.getDeclaredCurrency());
        m.put("inceptionDate",         t.getInceptionDate());
        m.put("expiryDate",            t.getExpiryDate());
        m.put("status",                t.getStatus());
        m.put("renewedFromTreatyId",   t.getRenewedFromTreatyId());
        m.put("aggregateLimit",        t.getAggregateLimit());
        m.put("aggregateLimitCurrency", t.getAggregateLimitCurrency());
        m.put("expectedAnnualPremium", t.getExpectedAnnualPremium());
        m.put("producerRef",           t.getProducerRef());
        return m;
    }

    private String[] diff(Map<String, Object> before, Map<String, Object> after) {
        if (before == null || after == null) return new String[0];
        return before.keySet().stream()
                .filter(k -> !Objects.equals(before.get(k), after.get(k)))
                .toArray(String[]::new);
    }

    private Mono<Void> publishAudit(String action, Treaty entity,
                                    Map<String, Object> before, Map<String, Object> after,
                                    String actorId, String actorEmail) {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            var event = AuditEvent.create(
                    tenantId != null ? tenantId : "unknown",
                    ENTITY_TYPE,
                    entity.getId().toString(),
                    entity.getTreatyRef(),
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
