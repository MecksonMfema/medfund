package com.medfund.user.service;

import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.tenant.TenantContext;
import com.medfund.user.dto.CreatePropertyRequest;
import com.medfund.user.dto.UpdatePropertyRequest;
import com.medfund.user.entity.Property;
import com.medfund.user.exception.PropertyNotFoundException;
import com.medfund.user.repository.PropertyRepository;
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
public class PropertyService {

    private final PropertyRepository propertyRepository;
    private final R2dbcEntityTemplate r2dbcTemplate;
    private final AuditPublisher auditPublisher;

    public Flux<Property> findAll() {
        return propertyRepository.findAllOrderByCreatedAtDesc();
    }

    public Mono<Property> findById(UUID id) {
        return propertyRepository.findById(id)
                .switchIfEmpty(Mono.error(new PropertyNotFoundException(id)));
    }

    public Flux<Property> findBySchemeId(UUID schemeId) {
        return propertyRepository.findBySchemeId(schemeId);
    }

    public Flux<Property> findByOwnerMemberId(UUID ownerMemberId) {
        return propertyRepository.findByOwnerMemberId(ownerMemberId);
    }

    public Flux<Property> search(String q) {
        return propertyRepository.search(q);
    }

    @Transactional
    public Mono<Property> create(CreatePropertyRequest request, String actorId, String actorEmail) {
        var p = new Property();
        p.setSchemeId(request.schemeId());
        p.setGroupId(request.groupId());
        p.setOwnerMemberId(request.ownerMemberId());
        p.setPropertyName(request.propertyName());
        p.setAddress(request.address());
        p.setSumInsured(request.sumInsured());
        p.setConstructionType(request.constructionType());
        p.setRoofType(request.roofType());
        p.setLocationRiskBand(request.locationRiskBand());
        p.setSecurityFeaturesCount(request.securityFeaturesCount() != null ? request.securityFeaturesCount() : 0);
        p.setPropertyAgeYears(request.propertyAgeYears());
        p.setOccupancy(request.occupancy());
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
    public Mono<Property> update(UUID id, UpdatePropertyRequest request, String actorId, String actorEmail) {
        return propertyRepository.findById(id)
                .switchIfEmpty(Mono.error(new PropertyNotFoundException(id)))
                .flatMap(existing -> {
                    var previous = copy(existing);
                    if (request.propertyName() != null) existing.setPropertyName(request.propertyName());
                    if (request.address() != null) existing.setAddress(request.address());
                    if (request.sumInsured() != null) existing.setSumInsured(request.sumInsured());
                    if (request.constructionType() != null) existing.setConstructionType(request.constructionType());
                    if (request.roofType() != null) existing.setRoofType(request.roofType());
                    if (request.locationRiskBand() != null) existing.setLocationRiskBand(request.locationRiskBand());
                    if (request.securityFeaturesCount() != null) existing.setSecurityFeaturesCount(request.securityFeaturesCount());
                    if (request.propertyAgeYears() != null) existing.setPropertyAgeYears(request.propertyAgeYears());
                    if (request.occupancy() != null) existing.setOccupancy(request.occupancy());
                    if (request.schemeId() != null) existing.setSchemeId(request.schemeId());
                    if (request.groupId() != null) existing.setGroupId(request.groupId());
                    if (request.ownerMemberId() != null) existing.setOwnerMemberId(request.ownerMemberId());

                    applyOverride(existing,
                            request.billingOverrideAmount(),
                            request.billingOverrideReason(),
                            request.billingOverrideEffectiveFrom());

                    existing.setUpdatedAt(Instant.now());
                    existing.setUpdatedBy(safeParseUuid(actorId));

                    return propertyRepository.save(existing)
                            .flatMap(saved -> Mono.deferContextual(ctx -> {
                                String tenantId = TenantContext.get(ctx);
                                return publishAudit(tenantId, saved, previous, actorId, actorEmail, "UPDATE")
                                        .thenReturn(saved);
                            }));
                });
    }

    @Transactional
    public Mono<Property> suspend(UUID id, String actorId, String actorEmail) {
        return transitionStatus(id, "suspended", actorId, actorEmail);
    }

    @Transactional
    public Mono<Property> terminate(UUID id, String actorId, String actorEmail) {
        return transitionStatus(id, "terminated", actorId, actorEmail);
    }

    @Transactional
    public Mono<Property> clearBillingOverride(UUID id, String actorId, String actorEmail) {
        return propertyRepository.findById(id)
                .switchIfEmpty(Mono.error(new PropertyNotFoundException(id)))
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
                    return propertyRepository.save(existing)
                            .flatMap(saved -> Mono.deferContextual(ctx -> {
                                String tenantId = TenantContext.get(ctx);
                                return publishAudit(tenantId, saved, previous, actorId, actorEmail, "UPDATE")
                                        .thenReturn(saved);
                            }));
                });
    }

    private Mono<Property> transitionStatus(UUID id, String newStatus, String actorId, String actorEmail) {
        return propertyRepository.findById(id)
                .switchIfEmpty(Mono.error(new PropertyNotFoundException(id)))
                .flatMap(existing -> {
                    var previous = copy(existing);
                    existing.setStatus(newStatus);
                    existing.setUpdatedAt(Instant.now());
                    existing.setUpdatedBy(safeParseUuid(actorId));
                    return propertyRepository.save(existing)
                            .flatMap(saved -> Mono.deferContextual(ctx -> {
                                String tenantId = TenantContext.get(ctx);
                                return publishAudit(tenantId, saved, previous, actorId, actorEmail, "UPDATE")
                                        .thenReturn(saved);
                            }));
                });
    }

    private Mono<Void> publishAudit(String tenantId, Property current, Property previous,
                                     String actorId, String actorEmail, String action) {
        var event = AuditEvent.create(
                tenantId != null ? tenantId : "unknown",
                "Property",
                current.getId().toString(),
                current.getPropertyName(),
                action,
                actorId,
                actorEmail,
                previous != null ? Map.of(
                        "status", String.valueOf(previous.getStatus()),
                        "sumInsured", String.valueOf(previous.getSumInsured()),
                        "billingOverrideAmount", String.valueOf(previous.getBillingOverrideAmount())
                ) : null,
                Map.of(
                        "status", String.valueOf(current.getStatus()),
                        "propertyName", String.valueOf(current.getPropertyName()),
                        "sumInsured", String.valueOf(current.getSumInsured()),
                        "billingOverrideAmount", String.valueOf(current.getBillingOverrideAmount())
                ),
                new String[]{"status", "propertyName", "address", "sumInsured",
                        "billingOverrideAmount", "ownerMemberId"},
                UUID.randomUUID().toString()
        );
        return auditPublisher.publish(event);
    }

    private static void applyOverride(Property p, BigDecimal amount, String reason, LocalDate effectiveFrom) {
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

    private Property copy(Property src) {
        var c = new Property();
        c.setId(src.getId());
        c.setSchemeId(src.getSchemeId());
        c.setGroupId(src.getGroupId());
        c.setOwnerMemberId(src.getOwnerMemberId());
        c.setPropertyName(src.getPropertyName());
        c.setAddress(src.getAddress());
        c.setSumInsured(src.getSumInsured());
        c.setConstructionType(src.getConstructionType());
        c.setRoofType(src.getRoofType());
        c.setLocationRiskBand(src.getLocationRiskBand());
        c.setSecurityFeaturesCount(src.getSecurityFeaturesCount());
        c.setPropertyAgeYears(src.getPropertyAgeYears());
        c.setOccupancy(src.getOccupancy());
        c.setStatus(src.getStatus());
        c.setBillingOverrideAmount(src.getBillingOverrideAmount());
        c.setBillingOverrideReason(src.getBillingOverrideReason());
        c.setBillingOverrideEffectiveFrom(src.getBillingOverrideEffectiveFrom());
        return c;
    }
}
