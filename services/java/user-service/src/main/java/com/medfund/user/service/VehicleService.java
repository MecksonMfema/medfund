package com.medfund.user.service;

import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.tenant.TenantContext;
import com.medfund.user.dto.CreateVehicleRequest;
import com.medfund.user.dto.PageResponse;
import com.medfund.user.dto.UpdateVehicleRequest;
import com.medfund.user.dto.VehicleFilterParams;
import com.medfund.user.dto.VehicleRow;
import com.medfund.user.entity.Vehicle;
import com.medfund.user.exception.VehicleNotFoundException;
import com.medfund.user.repository.VehicleQueryRepository;
import com.medfund.user.repository.VehicleRepository;
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
public class VehicleService {

    private final VehicleRepository vehicleRepository;
    private final VehicleQueryRepository vehicleQueryRepository;
    private final R2dbcEntityTemplate r2dbcTemplate;
    private final AuditPublisher auditPublisher;

    public Flux<Vehicle> findAll() {
        return vehicleRepository.findAllOrderByCreatedAtDesc();
    }

    /** Server-side paginated vehicles list with joined scheme + owner names. */
    public Mono<PageResponse<VehicleRow>> searchPaged(VehicleFilterParams params) {
        int page = Math.max(params.page(), 0);
        int size = Math.min(Math.max(params.size(), 1), 200);
        int offset = page * size;
        return vehicleQueryRepository.search(params, size, offset)
                .collectList()
                .zipWith(vehicleQueryRepository.count(params))
                .map(t -> PageResponse.of(t.getT1(), t.getT2(), page, size));
    }

    public Mono<Vehicle> findById(UUID id) {
        return vehicleRepository.findById(id)
                .switchIfEmpty(Mono.error(new VehicleNotFoundException(id)));
    }

    public Flux<Vehicle> findBySchemeId(UUID schemeId) {
        return vehicleRepository.findBySchemeId(schemeId);
    }

    public Flux<Vehicle> findByOwnerMemberId(UUID ownerMemberId) {
        return vehicleRepository.findByOwnerMemberId(ownerMemberId);
    }

    public Flux<Vehicle> search(String q) {
        return vehicleRepository.search(q);
    }

    @Transactional
    public Mono<Vehicle> create(CreateVehicleRequest request, String actorId, String actorEmail) {
        var v = new Vehicle();
        v.setSchemeId(request.schemeId());
        v.setGroupId(request.groupId());
        v.setOwnerMemberId(request.ownerMemberId());
        v.setRegistrationNumber(request.registrationNumber());
        v.setMake(request.make());
        v.setModel(request.model());
        v.setYear(request.year());
        v.setVehicleValue(request.vehicleValue());
        v.setBodyType(request.bodyType());
        v.setUsageType(request.usageType());
        v.setStatus("active");
        v.setCreatedAt(Instant.now());
        v.setUpdatedAt(Instant.now());
        UUID actorUuid = safeParseUuid(actorId);
        v.setCreatedBy(actorUuid);
        v.setUpdatedBy(actorUuid);

        return r2dbcTemplate.insert(v)
                .flatMap(saved -> Mono.deferContextual(ctx -> {
                    String tenantId = TenantContext.get(ctx);
                    return publishAudit(tenantId, saved, null, actorId, actorEmail, "CREATE")
                            .thenReturn(saved);
                }));
    }

    @Transactional
    public Mono<Vehicle> update(UUID id, UpdateVehicleRequest request, String actorId, String actorEmail) {
        return vehicleRepository.findById(id)
                .switchIfEmpty(Mono.error(new VehicleNotFoundException(id)))
                .flatMap(existing -> {
                    var previous = copy(existing);
                    if (request.make() != null) existing.setMake(request.make());
                    if (request.model() != null) existing.setModel(request.model());
                    if (request.year() != null) existing.setYear(request.year());
                    if (request.vehicleValue() != null) existing.setVehicleValue(request.vehicleValue());
                    if (request.bodyType() != null) existing.setBodyType(request.bodyType());
                    if (request.usageType() != null) existing.setUsageType(request.usageType());
                    if (request.schemeId() != null) existing.setSchemeId(request.schemeId());
                    if (request.groupId() != null) existing.setGroupId(request.groupId());
                    if (request.ownerMemberId() != null) existing.setOwnerMemberId(request.ownerMemberId());

                    applyOverride(existing,
                            request.billingOverrideAmount(),
                            request.billingOverrideReason(),
                            request.billingOverrideEffectiveFrom());

                    existing.setUpdatedAt(Instant.now());
                    existing.setUpdatedBy(safeParseUuid(actorId));

                    return vehicleRepository.save(existing)
                            .flatMap(saved -> Mono.deferContextual(ctx -> {
                                String tenantId = TenantContext.get(ctx);
                                return publishAudit(tenantId, saved, previous, actorId, actorEmail, "UPDATE")
                                        .thenReturn(saved);
                            }));
                });
    }

    @Transactional
    public Mono<Vehicle> suspend(UUID id, String actorId, String actorEmail) {
        return transitionStatus(id, "suspended", actorId, actorEmail);
    }

    @Transactional
    public Mono<Vehicle> terminate(UUID id, String actorId, String actorEmail) {
        return transitionStatus(id, "terminated", actorId, actorEmail);
    }

    @Transactional
    public Mono<Vehicle> clearBillingOverride(UUID id, String actorId, String actorEmail) {
        return vehicleRepository.findById(id)
                .switchIfEmpty(Mono.error(new VehicleNotFoundException(id)))
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
                    return vehicleRepository.save(existing)
                            .flatMap(saved -> Mono.deferContextual(ctx -> {
                                String tenantId = TenantContext.get(ctx);
                                return publishAudit(tenantId, saved, previous, actorId, actorEmail, "UPDATE")
                                        .thenReturn(saved);
                            }));
                });
    }

    private Mono<Vehicle> transitionStatus(UUID id, String newStatus, String actorId, String actorEmail) {
        return vehicleRepository.findById(id)
                .switchIfEmpty(Mono.error(new VehicleNotFoundException(id)))
                .flatMap(existing -> {
                    var previous = copy(existing);
                    existing.setStatus(newStatus);
                    existing.setUpdatedAt(Instant.now());
                    existing.setUpdatedBy(safeParseUuid(actorId));
                    return vehicleRepository.save(existing)
                            .flatMap(saved -> Mono.deferContextual(ctx -> {
                                String tenantId = TenantContext.get(ctx);
                                return publishAudit(tenantId, saved, previous, actorId, actorEmail, "UPDATE")
                                        .thenReturn(saved);
                            }));
                });
    }

    private Mono<Void> publishAudit(String tenantId, Vehicle current, Vehicle previous,
                                     String actorId, String actorEmail, String action) {
        var event = AuditEvent.create(
                tenantId != null ? tenantId : "unknown",
                "Vehicle",
                current.getId().toString(),
                current.getRegistrationNumber(),
                action,
                actorId,
                actorEmail,
                previous != null ? Map.of(
                        "status", String.valueOf(previous.getStatus()),
                        "vehicleValue", String.valueOf(previous.getVehicleValue()),
                        "billingOverrideAmount", String.valueOf(previous.getBillingOverrideAmount())
                ) : null,
                Map.of(
                        "status", String.valueOf(current.getStatus()),
                        "registrationNumber", String.valueOf(current.getRegistrationNumber()),
                        "vehicleValue", String.valueOf(current.getVehicleValue()),
                        "billingOverrideAmount", String.valueOf(current.getBillingOverrideAmount())
                ),
                new String[]{"status", "make", "model", "year", "vehicleValue",
                        "billingOverrideAmount", "ownerMemberId"},
                UUID.randomUUID().toString()
        );
        return auditPublisher.publish(event);
    }

    private static void applyOverride(Vehicle v, BigDecimal amount, String reason, LocalDate effectiveFrom) {
        if (amount == null) return;
        if (effectiveFrom == null) {
            throw new IllegalArgumentException(
                    "billingOverrideEffectiveFrom is required when billingOverrideAmount is set");
        }
        v.setBillingOverrideAmount(amount);
        v.setBillingOverrideReason(reason);
        v.setBillingOverrideEffectiveFrom(effectiveFrom);
    }

    private static UUID safeParseUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try { return UUID.fromString(s); }
        catch (IllegalArgumentException e) { return null; }
    }

    private Vehicle copy(Vehicle src) {
        var c = new Vehicle();
        c.setId(src.getId());
        c.setSchemeId(src.getSchemeId());
        c.setGroupId(src.getGroupId());
        c.setOwnerMemberId(src.getOwnerMemberId());
        c.setRegistrationNumber(src.getRegistrationNumber());
        c.setMake(src.getMake());
        c.setModel(src.getModel());
        c.setYear(src.getYear());
        c.setVehicleValue(src.getVehicleValue());
        c.setBodyType(src.getBodyType());
        c.setUsageType(src.getUsageType());
        c.setStatus(src.getStatus());
        c.setBillingOverrideAmount(src.getBillingOverrideAmount());
        c.setBillingOverrideReason(src.getBillingOverrideReason());
        c.setBillingOverrideEffectiveFrom(src.getBillingOverrideEffectiveFrom());
        return c;
    }
}
