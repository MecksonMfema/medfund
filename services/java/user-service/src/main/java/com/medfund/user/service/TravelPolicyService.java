package com.medfund.user.service;

import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.tenant.TenantContext;
import com.medfund.user.dto.CreateTravelPolicyRequest;
import com.medfund.user.dto.UpdateTravelPolicyRequest;
import com.medfund.user.entity.TravelPolicy;
import com.medfund.user.exception.TravelPolicyNotFoundException;
import com.medfund.user.repository.TravelPolicyRepository;
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
public class TravelPolicyService {

    private final TravelPolicyRepository travelPolicyRepository;
    private final R2dbcEntityTemplate r2dbcTemplate;
    private final AuditPublisher auditPublisher;

    public Flux<TravelPolicy> findAll() {
        return travelPolicyRepository.findAllOrderByCreatedAtDesc();
    }

    public Mono<TravelPolicy> findById(UUID id) {
        return travelPolicyRepository.findById(id)
                .switchIfEmpty(Mono.error(new TravelPolicyNotFoundException(id)));
    }

    public Flux<TravelPolicy> findBySchemeId(UUID schemeId) {
        return travelPolicyRepository.findBySchemeId(schemeId);
    }

    public Flux<TravelPolicy> findByTravelerMemberId(UUID travelerMemberId) {
        return travelPolicyRepository.findByTravelerMemberId(travelerMemberId);
    }

    public Flux<TravelPolicy> search(String q) {
        return travelPolicyRepository.search(q);
    }

    @Transactional
    public Mono<TravelPolicy> create(CreateTravelPolicyRequest request, String actorId, String actorEmail) {
        var t = new TravelPolicy();
        t.setSchemeId(request.schemeId());
        t.setGroupId(request.groupId());
        t.setTravelerMemberId(request.travelerMemberId());
        t.setPolicyNumber(request.policyNumber());
        t.setTripStartDate(request.tripStartDate());
        t.setTripEndDate(request.tripEndDate());
        t.setDestinationBand(request.destinationBand());
        t.setCoverageLevel(request.coverageLevel());
        t.setPreExistingDeclared(request.preExistingDeclared() != null ? request.preExistingDeclared() : Boolean.FALSE);
        t.setStatus("active");
        t.setCreatedAt(Instant.now());
        t.setUpdatedAt(Instant.now());
        UUID actorUuid = safeParseUuid(actorId);
        t.setCreatedBy(actorUuid);
        t.setUpdatedBy(actorUuid);

        return r2dbcTemplate.insert(t)
                .flatMap(saved -> Mono.deferContextual(ctx -> {
                    String tenantId = TenantContext.get(ctx);
                    return publishAudit(tenantId, saved, null, actorId, actorEmail, "CREATE")
                            .thenReturn(saved);
                }));
    }

    @Transactional
    public Mono<TravelPolicy> update(UUID id, UpdateTravelPolicyRequest request, String actorId, String actorEmail) {
        return travelPolicyRepository.findById(id)
                .switchIfEmpty(Mono.error(new TravelPolicyNotFoundException(id)))
                .flatMap(existing -> {
                    var previous = copy(existing);
                    if (request.policyNumber() != null) existing.setPolicyNumber(request.policyNumber());
                    if (request.tripStartDate() != null) existing.setTripStartDate(request.tripStartDate());
                    if (request.tripEndDate() != null) existing.setTripEndDate(request.tripEndDate());
                    if (request.destinationBand() != null) existing.setDestinationBand(request.destinationBand());
                    if (request.coverageLevel() != null) existing.setCoverageLevel(request.coverageLevel());
                    if (request.preExistingDeclared() != null) existing.setPreExistingDeclared(request.preExistingDeclared());
                    if (request.schemeId() != null) existing.setSchemeId(request.schemeId());
                    if (request.groupId() != null) existing.setGroupId(request.groupId());

                    applyOverride(existing,
                            request.billingOverrideAmount(),
                            request.billingOverrideReason(),
                            request.billingOverrideEffectiveFrom());

                    existing.setUpdatedAt(Instant.now());
                    existing.setUpdatedBy(safeParseUuid(actorId));

                    return travelPolicyRepository.save(existing)
                            .flatMap(saved -> Mono.deferContextual(ctx -> {
                                String tenantId = TenantContext.get(ctx);
                                return publishAudit(tenantId, saved, previous, actorId, actorEmail, "UPDATE")
                                        .thenReturn(saved);
                            }));
                });
    }

    @Transactional
    public Mono<TravelPolicy> suspend(UUID id, String actorId, String actorEmail) {
        return transitionStatus(id, "suspended", actorId, actorEmail);
    }

    @Transactional
    public Mono<TravelPolicy> terminate(UUID id, String actorId, String actorEmail) {
        return transitionStatus(id, "terminated", actorId, actorEmail);
    }

    @Transactional
    public Mono<TravelPolicy> clearBillingOverride(UUID id, String actorId, String actorEmail) {
        return travelPolicyRepository.findById(id)
                .switchIfEmpty(Mono.error(new TravelPolicyNotFoundException(id)))
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
                    return travelPolicyRepository.save(existing)
                            .flatMap(saved -> Mono.deferContextual(ctx -> {
                                String tenantId = TenantContext.get(ctx);
                                return publishAudit(tenantId, saved, previous, actorId, actorEmail, "UPDATE")
                                        .thenReturn(saved);
                            }));
                });
    }

    private Mono<TravelPolicy> transitionStatus(UUID id, String newStatus, String actorId, String actorEmail) {
        return travelPolicyRepository.findById(id)
                .switchIfEmpty(Mono.error(new TravelPolicyNotFoundException(id)))
                .flatMap(existing -> {
                    var previous = copy(existing);
                    existing.setStatus(newStatus);
                    existing.setUpdatedAt(Instant.now());
                    existing.setUpdatedBy(safeParseUuid(actorId));
                    return travelPolicyRepository.save(existing)
                            .flatMap(saved -> Mono.deferContextual(ctx -> {
                                String tenantId = TenantContext.get(ctx);
                                return publishAudit(tenantId, saved, previous, actorId, actorEmail, "UPDATE")
                                        .thenReturn(saved);
                            }));
                });
    }

    private Mono<Void> publishAudit(String tenantId, TravelPolicy current, TravelPolicy previous,
                                     String actorId, String actorEmail, String action) {
        var event = AuditEvent.create(
                tenantId != null ? tenantId : "unknown",
                "TravelPolicy",
                current.getId().toString(),
                current.getPolicyNumber(),
                action,
                actorId,
                actorEmail,
                previous != null ? Map.of(
                        "status", String.valueOf(previous.getStatus()),
                        "tripStartDate", String.valueOf(previous.getTripStartDate()),
                        "tripEndDate", String.valueOf(previous.getTripEndDate()),
                        "billingOverrideAmount", String.valueOf(previous.getBillingOverrideAmount())
                ) : null,
                Map.of(
                        "status", String.valueOf(current.getStatus()),
                        "policyNumber", String.valueOf(current.getPolicyNumber()),
                        "tripStartDate", String.valueOf(current.getTripStartDate()),
                        "tripEndDate", String.valueOf(current.getTripEndDate()),
                        "billingOverrideAmount", String.valueOf(current.getBillingOverrideAmount())
                ),
                new String[]{"status", "policyNumber", "tripStartDate", "tripEndDate",
                        "destinationBand", "coverageLevel", "preExistingDeclared",
                        "billingOverrideAmount", "travelerMemberId"},
                UUID.randomUUID().toString()
        );
        return auditPublisher.publish(event);
    }

    private static void applyOverride(TravelPolicy t, BigDecimal amount, String reason, LocalDate effectiveFrom) {
        if (amount == null) return;
        if (effectiveFrom == null) {
            throw new IllegalArgumentException(
                    "billingOverrideEffectiveFrom is required when billingOverrideAmount is set");
        }
        t.setBillingOverrideAmount(amount);
        t.setBillingOverrideReason(reason);
        t.setBillingOverrideEffectiveFrom(effectiveFrom);
    }

    private static UUID safeParseUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try { return UUID.fromString(s); }
        catch (IllegalArgumentException e) { return null; }
    }

    private TravelPolicy copy(TravelPolicy src) {
        var c = new TravelPolicy();
        c.setId(src.getId());
        c.setSchemeId(src.getSchemeId());
        c.setGroupId(src.getGroupId());
        c.setTravelerMemberId(src.getTravelerMemberId());
        c.setPolicyNumber(src.getPolicyNumber());
        c.setTripStartDate(src.getTripStartDate());
        c.setTripEndDate(src.getTripEndDate());
        c.setDestinationBand(src.getDestinationBand());
        c.setCoverageLevel(src.getCoverageLevel());
        c.setPreExistingDeclared(src.getPreExistingDeclared());
        c.setStatus(src.getStatus());
        c.setBillingOverrideAmount(src.getBillingOverrideAmount());
        c.setBillingOverrideReason(src.getBillingOverrideReason());
        c.setBillingOverrideEffectiveFrom(src.getBillingOverrideEffectiveFrom());
        return c;
    }
}
