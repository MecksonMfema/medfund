package com.medfund.user.service;

import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.tenant.TenantContext;
import com.medfund.user.dto.CreateFuneralPolicyRequest;
import com.medfund.user.dto.FuneralPolicyFilterParams;
import com.medfund.user.dto.FuneralPolicyRow;
import com.medfund.user.dto.PageResponse;
import com.medfund.user.dto.UpdateFuneralPolicyRequest;
import com.medfund.user.entity.FuneralPolicy;
import com.medfund.user.exception.FuneralPolicyNotFoundException;
import com.medfund.user.repository.FuneralPolicyQueryRepository;
import com.medfund.user.repository.FuneralPolicyRepository;
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
public class FuneralPolicyService {

    private final FuneralPolicyRepository funeralPolicyRepository;
    private final FuneralPolicyQueryRepository funeralPolicyQueryRepository;
    private final R2dbcEntityTemplate r2dbcTemplate;
    private final AuditPublisher auditPublisher;

    public Flux<FuneralPolicy> findAll() {
        return funeralPolicyRepository.findAllOrderByCreatedAtDesc();
    }

    /** Server-side paginated funeral-policies list with joined scheme + principal names. */
    public Mono<PageResponse<FuneralPolicyRow>> searchPaged(FuneralPolicyFilterParams params) {
        int page = Math.max(params.page(), 0);
        int size = Math.min(Math.max(params.size(), 1), 200);
        int offset = page * size;
        return funeralPolicyQueryRepository.search(params, size, offset)
                .collectList()
                .zipWith(funeralPolicyQueryRepository.count(params))
                .map(t -> PageResponse.of(t.getT1(), t.getT2(), page, size));
    }

    public Mono<FuneralPolicy> findById(UUID id) {
        return funeralPolicyRepository.findById(id)
                .switchIfEmpty(Mono.error(new FuneralPolicyNotFoundException(id)));
    }

    public Flux<FuneralPolicy> findBySchemeId(UUID schemeId) {
        return funeralPolicyRepository.findBySchemeId(schemeId);
    }

    public Flux<FuneralPolicy> findByPrincipalMemberId(UUID principalMemberId) {
        return funeralPolicyRepository.findByPrincipalMemberId(principalMemberId);
    }

    public Flux<FuneralPolicy> search(String q) {
        return funeralPolicyRepository.search(q);
    }

    @Transactional
    public Mono<FuneralPolicy> create(CreateFuneralPolicyRequest request, String actorId, String actorEmail) {
        var p = new FuneralPolicy();
        p.setSchemeId(request.schemeId());
        p.setGroupId(request.groupId());
        p.setPrincipalMemberId(request.principalMemberId());
        p.setPolicyNumber(request.policyNumber());
        p.setCoverAmount(request.coverAmount());
        p.setLivesCovered(request.livesCovered());
        p.setHealthDeclaration(request.healthDeclaration());
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
    public Mono<FuneralPolicy> update(UUID id, UpdateFuneralPolicyRequest request, String actorId, String actorEmail) {
        return funeralPolicyRepository.findById(id)
                .switchIfEmpty(Mono.error(new FuneralPolicyNotFoundException(id)))
                .flatMap(existing -> {
                    var previous = copy(existing);
                    if (request.coverAmount() != null) existing.setCoverAmount(request.coverAmount());
                    if (request.livesCovered() != null) existing.setLivesCovered(request.livesCovered());
                    if (request.healthDeclaration() != null) existing.setHealthDeclaration(request.healthDeclaration());
                    if (request.schemeId() != null) existing.setSchemeId(request.schemeId());
                    if (request.groupId() != null) existing.setGroupId(request.groupId());

                    applyOverride(existing,
                            request.billingOverrideAmount(),
                            request.billingOverrideReason(),
                            request.billingOverrideEffectiveFrom());

                    existing.setUpdatedAt(Instant.now());
                    existing.setUpdatedBy(safeParseUuid(actorId));

                    return funeralPolicyRepository.save(existing)
                            .flatMap(saved -> Mono.deferContextual(ctx -> {
                                String tenantId = TenantContext.get(ctx);
                                return publishAudit(tenantId, saved, previous, actorId, actorEmail, "UPDATE")
                                        .thenReturn(saved);
                            }));
                });
    }

    @Transactional
    public Mono<FuneralPolicy> suspend(UUID id, String actorId, String actorEmail) {
        return transitionStatus(id, "suspended", actorId, actorEmail);
    }

    @Transactional
    public Mono<FuneralPolicy> terminate(UUID id, String actorId, String actorEmail) {
        return transitionStatus(id, "terminated", actorId, actorEmail);
    }

    @Transactional
    public Mono<FuneralPolicy> clearBillingOverride(UUID id, String actorId, String actorEmail) {
        return funeralPolicyRepository.findById(id)
                .switchIfEmpty(Mono.error(new FuneralPolicyNotFoundException(id)))
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
                    return funeralPolicyRepository.save(existing)
                            .flatMap(saved -> Mono.deferContextual(ctx -> {
                                String tenantId = TenantContext.get(ctx);
                                return publishAudit(tenantId, saved, previous, actorId, actorEmail, "UPDATE")
                                        .thenReturn(saved);
                            }));
                });
    }

    private Mono<FuneralPolicy> transitionStatus(UUID id, String newStatus, String actorId, String actorEmail) {
        return funeralPolicyRepository.findById(id)
                .switchIfEmpty(Mono.error(new FuneralPolicyNotFoundException(id)))
                .flatMap(existing -> {
                    var previous = copy(existing);
                    existing.setStatus(newStatus);
                    existing.setUpdatedAt(Instant.now());
                    existing.setUpdatedBy(safeParseUuid(actorId));
                    return funeralPolicyRepository.save(existing)
                            .flatMap(saved -> Mono.deferContextual(ctx -> {
                                String tenantId = TenantContext.get(ctx);
                                return publishAudit(tenantId, saved, previous, actorId, actorEmail, "UPDATE")
                                        .thenReturn(saved);
                            }));
                });
    }

    private Mono<Void> publishAudit(String tenantId, FuneralPolicy current, FuneralPolicy previous,
                                     String actorId, String actorEmail, String action) {
        var event = AuditEvent.create(
                tenantId != null ? tenantId : "unknown",
                "FuneralPolicy",
                current.getId().toString(),
                current.getPolicyNumber(),
                action,
                actorId,
                actorEmail,
                previous != null ? Map.of(
                        "status", String.valueOf(previous.getStatus()),
                        "coverAmount", String.valueOf(previous.getCoverAmount()),
                        "billingOverrideAmount", String.valueOf(previous.getBillingOverrideAmount())
                ) : null,
                Map.of(
                        "status", String.valueOf(current.getStatus()),
                        "policyNumber", String.valueOf(current.getPolicyNumber()),
                        "coverAmount", String.valueOf(current.getCoverAmount()),
                        "billingOverrideAmount", String.valueOf(current.getBillingOverrideAmount())
                ),
                new String[]{"status", "coverAmount", "livesCovered", "healthDeclaration",
                        "billingOverrideAmount", "principalMemberId"},
                UUID.randomUUID().toString()
        );
        return auditPublisher.publish(event);
    }

    private static void applyOverride(FuneralPolicy p, BigDecimal amount, String reason, LocalDate effectiveFrom) {
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

    private FuneralPolicy copy(FuneralPolicy src) {
        var c = new FuneralPolicy();
        c.setId(src.getId());
        c.setSchemeId(src.getSchemeId());
        c.setGroupId(src.getGroupId());
        c.setPrincipalMemberId(src.getPrincipalMemberId());
        c.setPolicyNumber(src.getPolicyNumber());
        c.setCoverAmount(src.getCoverAmount());
        c.setLivesCovered(src.getLivesCovered());
        c.setHealthDeclaration(src.getHealthDeclaration());
        c.setStatus(src.getStatus());
        c.setBillingOverrideAmount(src.getBillingOverrideAmount());
        c.setBillingOverrideReason(src.getBillingOverrideReason());
        c.setBillingOverrideEffectiveFrom(src.getBillingOverrideEffectiveFrom());
        return c;
    }
}
