package com.medfund.user.service;

import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.tenant.TenantContext;
import com.medfund.user.dto.CreateLifePolicyRequest;
import com.medfund.user.dto.LifePolicyFilterParams;
import com.medfund.user.dto.LifePolicyRow;
import com.medfund.user.dto.PageResponse;
import com.medfund.user.dto.UpdateLifePolicyRequest;
import com.medfund.user.entity.LifePolicy;
import com.medfund.user.exception.LifePolicyNotFoundException;
import com.medfund.user.repository.LifePolicyQueryRepository;
import com.medfund.user.repository.LifePolicyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class LifePolicyService {

    private final LifePolicyRepository lifePolicyRepository;
    private final LifePolicyQueryRepository lifePolicyQueryRepository;
    private final R2dbcEntityTemplate r2dbcTemplate;
    private final AuditPublisher auditPublisher;

    public Flux<LifePolicy> findAll() {
        return lifePolicyRepository.findAllOrderByCreatedAtDesc();
    }

    /** Server-side paginated life-policies list with joined scheme + insured names. */
    public Mono<PageResponse<LifePolicyRow>> searchPaged(LifePolicyFilterParams params) {
        int page = Math.max(params.page(), 0);
        int size = Math.min(Math.max(params.size(), 1), 200);
        int offset = page * size;
        return lifePolicyQueryRepository.search(params, size, offset)
                .collectList()
                .zipWith(lifePolicyQueryRepository.count(params))
                .map(t -> PageResponse.of(t.getT1(), t.getT2(), page, size));
    }

    public Mono<LifePolicy> findById(UUID id) {
        return lifePolicyRepository.findById(id)
                .switchIfEmpty(Mono.error(new LifePolicyNotFoundException(id)));
    }

    public Flux<LifePolicy> findBySchemeId(UUID schemeId) {
        return lifePolicyRepository.findBySchemeId(schemeId);
    }

    public Flux<LifePolicy> findByInsuredMemberId(UUID insuredMemberId) {
        return lifePolicyRepository.findByInsuredMemberId(insuredMemberId);
    }

    public Flux<LifePolicy> search(String q) {
        return lifePolicyRepository.search(q);
    }

    @Transactional
    public Mono<LifePolicy> create(CreateLifePolicyRequest request, String actorId, String actorEmail) {
        var p = new LifePolicy();
        p.setSchemeId(request.schemeId());
        p.setGroupId(request.groupId());
        p.setInsuredMemberId(request.insuredMemberId());
        p.setPolicyNumber(request.policyNumber());
        p.setSumAssured(request.sumAssured());
        p.setOccupationHazardClass(request.occupationHazardClass());
        p.setTermMonths(request.termMonths());
        p.setStatus("active");
        p.setCreatedAt(Instant.now());
        p.setUpdatedAt(Instant.now());
        UUID actorUuid = safeParseUuid(actorId);
        p.setCreatedBy(actorUuid);
        p.setUpdatedBy(actorUuid);

        return r2dbcTemplate.insert(p)
                .flatMap(saved -> Mono.deferContextual(ctx -> {
                    String tenantId = TenantContext.get(ctx);
                    return publishAudit(tenantId, saved, null, actorId, actorEmail, "CREATE")
                            .thenReturn(saved);
                }));
    }

    @Transactional
    public Mono<LifePolicy> update(UUID id, UpdateLifePolicyRequest request, String actorId, String actorEmail) {
        return lifePolicyRepository.findById(id)
                .switchIfEmpty(Mono.error(new LifePolicyNotFoundException(id)))
                .flatMap(existing -> {
                    var previous = copy(existing);
                    if (request.sumAssured() != null) existing.setSumAssured(request.sumAssured());
                    if (request.occupationHazardClass() != null) existing.setOccupationHazardClass(request.occupationHazardClass());
                    if (request.termMonths() != null) existing.setTermMonths(request.termMonths());
                    if (request.schemeId() != null) existing.setSchemeId(request.schemeId());
                    if (request.groupId() != null) existing.setGroupId(request.groupId());

                    applyOverride(existing,
                            request.billingOverrideAmount(),
                            request.billingOverrideReason(),
                            request.billingOverrideEffectiveFrom());

                    existing.setUpdatedAt(Instant.now());
                    existing.setUpdatedBy(safeParseUuid(actorId));

                    return lifePolicyRepository.save(existing)
                            .flatMap(saved -> Mono.deferContextual(ctx -> {
                                String tenantId = TenantContext.get(ctx);
                                return publishAudit(tenantId, saved, previous, actorId, actorEmail, "UPDATE")
                                        .thenReturn(saved);
                            }));
                });
    }

    @Transactional
    public Mono<LifePolicy> suspend(UUID id, String actorId, String actorEmail) {
        return transitionStatus(id, "suspended", actorId, actorEmail);
    }

    @Transactional
    public Mono<LifePolicy> terminate(UUID id, String actorId, String actorEmail) {
        return transitionStatus(id, "terminated", actorId, actorEmail);
    }

    @Transactional
    public Mono<LifePolicy> clearBillingOverride(UUID id, String actorId, String actorEmail) {
        return lifePolicyRepository.findById(id)
                .switchIfEmpty(Mono.error(new LifePolicyNotFoundException(id)))
                .flatMap(existing -> {
                    if (existing.getBillingOverrideAmount() == null) {
                        return Mono.just(existing);
                    }
                    var previous = copy(existing);
                    existing.setBillingOverrideAmount(null);
                    existing.setBillingOverrideReason(null);
                    existing.setBillingOverrideEffectiveFrom(null);
                    existing.setUpdatedAt(Instant.now());
                    existing.setUpdatedBy(safeParseUuid(actorId));
                    return lifePolicyRepository.save(existing)
                            .flatMap(saved -> Mono.deferContextual(ctx -> {
                                String tenantId = TenantContext.get(ctx);
                                return publishAudit(tenantId, saved, previous, actorId, actorEmail, "UPDATE")
                                        .thenReturn(saved);
                            }));
                });
    }

    private Mono<LifePolicy> transitionStatus(UUID id, String newStatus, String actorId, String actorEmail) {
        return lifePolicyRepository.findById(id)
                .switchIfEmpty(Mono.error(new LifePolicyNotFoundException(id)))
                .flatMap(existing -> {
                    var previous = copy(existing);
                    existing.setStatus(newStatus);
                    existing.setUpdatedAt(Instant.now());
                    existing.setUpdatedBy(safeParseUuid(actorId));
                    return lifePolicyRepository.save(existing)
                            .flatMap(saved -> Mono.deferContextual(ctx -> {
                                String tenantId = TenantContext.get(ctx);
                                return publishAudit(tenantId, saved, previous, actorId, actorEmail, "UPDATE")
                                        .thenReturn(saved);
                            }));
                });
    }

    private Mono<Void> publishAudit(String tenantId, LifePolicy current, LifePolicy previous,
                                     String actorId, String actorEmail, String action) {
        var event = AuditEvent.create(
                tenantId != null ? tenantId : "unknown",
                "LifePolicy",
                current.getId().toString(),
                current.getPolicyNumber(),
                action,
                actorId,
                actorEmail,
                previous != null ? Map.of(
                        "status", String.valueOf(previous.getStatus()),
                        "sumAssured", String.valueOf(previous.getSumAssured()),
                        "billingOverrideAmount", String.valueOf(previous.getBillingOverrideAmount())
                ) : null,
                Map.of(
                        "status", String.valueOf(current.getStatus()),
                        "policyNumber", String.valueOf(current.getPolicyNumber()),
                        "sumAssured", String.valueOf(current.getSumAssured()),
                        "billingOverrideAmount", String.valueOf(current.getBillingOverrideAmount())
                ),
                new String[]{"status", "sumAssured", "occupationHazardClass", "termMonths",
                        "billingOverrideAmount", "insuredMemberId"},
                UUID.randomUUID().toString()
        );
        return auditPublisher.publish(event);
    }

    private static void applyOverride(LifePolicy p, BigDecimal amount, String reason, LocalDate effectiveFrom) {
        if (amount == null) return;
        if (effectiveFrom == null) {
            throw new IllegalArgumentException(
                    "billingOverrideEffectiveFrom is required when billingOverrideAmount is set");
        }
        p.setBillingOverrideAmount(amount);
        p.setBillingOverrideReason(reason);
        p.setBillingOverrideEffectiveFrom(effectiveFrom);
    }

    private static UUID safeParseUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try { return UUID.fromString(s); }
        catch (IllegalArgumentException e) { return null; }
    }

    private LifePolicy copy(LifePolicy src) {
        var c = new LifePolicy();
        c.setId(src.getId());
        c.setSchemeId(src.getSchemeId());
        c.setGroupId(src.getGroupId());
        c.setInsuredMemberId(src.getInsuredMemberId());
        c.setPolicyNumber(src.getPolicyNumber());
        c.setSumAssured(src.getSumAssured());
        c.setOccupationHazardClass(src.getOccupationHazardClass());
        c.setTermMonths(src.getTermMonths());
        c.setStatus(src.getStatus());
        c.setBillingOverrideAmount(src.getBillingOverrideAmount());
        c.setBillingOverrideReason(src.getBillingOverrideReason());
        c.setBillingOverrideEffectiveFrom(src.getBillingOverrideEffectiveFrom());
        return c;
    }
}
