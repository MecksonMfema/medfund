package com.medfund.user.service;

import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.tenant.TenantContext;
import com.medfund.user.dto.CreateDisabilityPolicyRequest;
import com.medfund.user.dto.UpdateDisabilityPolicyRequest;
import com.medfund.user.entity.DisabilityPolicy;
import com.medfund.user.exception.DisabilityPolicyNotFoundException;
import com.medfund.user.repository.DisabilityPolicyRepository;
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
public class DisabilityPolicyService {

    private final DisabilityPolicyRepository disabilityPolicyRepository;
    private final R2dbcEntityTemplate r2dbcTemplate;
    private final AuditPublisher auditPublisher;

    public Flux<DisabilityPolicy> findAll() {
        return disabilityPolicyRepository.findAllOrderByCreatedAtDesc();
    }

    public Mono<DisabilityPolicy> findById(UUID id) {
        return disabilityPolicyRepository.findById(id)
                .switchIfEmpty(Mono.error(new DisabilityPolicyNotFoundException(id)));
    }

    public Flux<DisabilityPolicy> findBySchemeId(UUID schemeId) {
        return disabilityPolicyRepository.findBySchemeId(schemeId);
    }

    public Flux<DisabilityPolicy> findByInsuredMemberId(UUID insuredMemberId) {
        return disabilityPolicyRepository.findByInsuredMemberId(insuredMemberId);
    }

    public Flux<DisabilityPolicy> search(String q) {
        return disabilityPolicyRepository.search(q);
    }

    @Transactional
    public Mono<DisabilityPolicy> create(CreateDisabilityPolicyRequest request, String actorId, String actorEmail) {
        var d = new DisabilityPolicy();
        d.setSchemeId(request.schemeId());
        d.setGroupId(request.groupId());
        d.setInsuredMemberId(request.insuredMemberId());
        d.setPolicyNumber(request.policyNumber());
        d.setOccupationHazardClass(request.occupationHazardClass());
        d.setWaitingPeriodDays(request.waitingPeriodDays());
        d.setBenefitPeriod(request.benefitPeriod());
        d.setMonthlyBenefit(request.monthlyBenefit());
        d.setStatus("active");
        d.setCreatedAt(Instant.now());
        d.setUpdatedAt(Instant.now());
        UUID actorUuid = safeParseUuid(actorId);
        d.setCreatedBy(actorUuid);
        d.setUpdatedBy(actorUuid);

        return r2dbcTemplate.insert(d)
                .flatMap(saved -> Mono.deferContextual(ctx -> {
                    String tenantId = TenantContext.get(ctx);
                    return publishAudit(tenantId, saved, null, actorId, actorEmail, "CREATE")
                            .thenReturn(saved);
                }));
    }

    @Transactional
    public Mono<DisabilityPolicy> update(UUID id, UpdateDisabilityPolicyRequest request, String actorId, String actorEmail) {
        return disabilityPolicyRepository.findById(id)
                .switchIfEmpty(Mono.error(new DisabilityPolicyNotFoundException(id)))
                .flatMap(existing -> {
                    var previous = copy(existing);
                    if (request.policyNumber() != null) existing.setPolicyNumber(request.policyNumber());
                    if (request.occupationHazardClass() != null) existing.setOccupationHazardClass(request.occupationHazardClass());
                    if (request.waitingPeriodDays() != null) existing.setWaitingPeriodDays(request.waitingPeriodDays());
                    if (request.benefitPeriod() != null) existing.setBenefitPeriod(request.benefitPeriod());
                    if (request.monthlyBenefit() != null) existing.setMonthlyBenefit(request.monthlyBenefit());
                    if (request.schemeId() != null) existing.setSchemeId(request.schemeId());
                    if (request.groupId() != null) existing.setGroupId(request.groupId());

                    applyOverride(existing,
                            request.billingOverrideAmount(),
                            request.billingOverrideReason(),
                            request.billingOverrideEffectiveFrom());

                    existing.setUpdatedAt(Instant.now());
                    existing.setUpdatedBy(safeParseUuid(actorId));

                    return disabilityPolicyRepository.save(existing)
                            .flatMap(saved -> Mono.deferContextual(ctx -> {
                                String tenantId = TenantContext.get(ctx);
                                return publishAudit(tenantId, saved, previous, actorId, actorEmail, "UPDATE")
                                        .thenReturn(saved);
                            }));
                });
    }

    @Transactional
    public Mono<DisabilityPolicy> suspend(UUID id, String actorId, String actorEmail) {
        return transitionStatus(id, "suspended", actorId, actorEmail);
    }

    @Transactional
    public Mono<DisabilityPolicy> terminate(UUID id, String actorId, String actorEmail) {
        return transitionStatus(id, "terminated", actorId, actorEmail);
    }

    @Transactional
    public Mono<DisabilityPolicy> clearBillingOverride(UUID id, String actorId, String actorEmail) {
        return disabilityPolicyRepository.findById(id)
                .switchIfEmpty(Mono.error(new DisabilityPolicyNotFoundException(id)))
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
                    return disabilityPolicyRepository.save(existing)
                            .flatMap(saved -> Mono.deferContextual(ctx -> {
                                String tenantId = TenantContext.get(ctx);
                                return publishAudit(tenantId, saved, previous, actorId, actorEmail, "UPDATE")
                                        .thenReturn(saved);
                            }));
                });
    }

    private Mono<DisabilityPolicy> transitionStatus(UUID id, String newStatus, String actorId, String actorEmail) {
        return disabilityPolicyRepository.findById(id)
                .switchIfEmpty(Mono.error(new DisabilityPolicyNotFoundException(id)))
                .flatMap(existing -> {
                    var previous = copy(existing);
                    existing.setStatus(newStatus);
                    existing.setUpdatedAt(Instant.now());
                    existing.setUpdatedBy(safeParseUuid(actorId));
                    return disabilityPolicyRepository.save(existing)
                            .flatMap(saved -> Mono.deferContextual(ctx -> {
                                String tenantId = TenantContext.get(ctx);
                                return publishAudit(tenantId, saved, previous, actorId, actorEmail, "UPDATE")
                                        .thenReturn(saved);
                            }));
                });
    }

    private Mono<Void> publishAudit(String tenantId, DisabilityPolicy current, DisabilityPolicy previous,
                                     String actorId, String actorEmail, String action) {
        var event = AuditEvent.create(
                tenantId != null ? tenantId : "unknown",
                "DisabilityPolicy",
                current.getId().toString(),
                current.getPolicyNumber(),
                action,
                actorId,
                actorEmail,
                previous != null ? Map.of(
                        "status", String.valueOf(previous.getStatus()),
                        "monthlyBenefit", String.valueOf(previous.getMonthlyBenefit()),
                        "billingOverrideAmount", String.valueOf(previous.getBillingOverrideAmount())
                ) : null,
                Map.of(
                        "status", String.valueOf(current.getStatus()),
                        "policyNumber", String.valueOf(current.getPolicyNumber()),
                        "monthlyBenefit", String.valueOf(current.getMonthlyBenefit()),
                        "billingOverrideAmount", String.valueOf(current.getBillingOverrideAmount())
                ),
                new String[]{"status", "policyNumber", "occupationHazardClass", "waitingPeriodDays",
                        "benefitPeriod", "monthlyBenefit", "billingOverrideAmount"},
                UUID.randomUUID().toString()
        );
        return auditPublisher.publish(event);
    }

    private static void applyOverride(DisabilityPolicy d, BigDecimal amount, String reason, LocalDate effectiveFrom) {
        if (amount == null) return;
        if (effectiveFrom == null) {
            throw new IllegalArgumentException(
                    "billingOverrideEffectiveFrom is required when billingOverrideAmount is set");
        }
        d.setBillingOverrideAmount(amount);
        d.setBillingOverrideReason(reason);
        d.setBillingOverrideEffectiveFrom(effectiveFrom);
    }

    private static UUID safeParseUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try { return UUID.fromString(s); }
        catch (IllegalArgumentException e) { return null; }
    }

    private DisabilityPolicy copy(DisabilityPolicy src) {
        var c = new DisabilityPolicy();
        c.setId(src.getId());
        c.setSchemeId(src.getSchemeId());
        c.setGroupId(src.getGroupId());
        c.setInsuredMemberId(src.getInsuredMemberId());
        c.setPolicyNumber(src.getPolicyNumber());
        c.setOccupationHazardClass(src.getOccupationHazardClass());
        c.setWaitingPeriodDays(src.getWaitingPeriodDays());
        c.setBenefitPeriod(src.getBenefitPeriod());
        c.setMonthlyBenefit(src.getMonthlyBenefit());
        c.setStatus(src.getStatus());
        c.setBillingOverrideAmount(src.getBillingOverrideAmount());
        c.setBillingOverrideReason(src.getBillingOverrideReason());
        c.setBillingOverrideEffectiveFrom(src.getBillingOverrideEffectiveFrom());
        return c;
    }
}
