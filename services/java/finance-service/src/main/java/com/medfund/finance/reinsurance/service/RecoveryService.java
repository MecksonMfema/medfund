package com.medfund.finance.reinsurance.service;

import com.medfund.finance.reinsurance.dto.RecoveryResponse;
import com.medfund.finance.reinsurance.entity.Recovery;
import com.medfund.finance.reinsurance.repository.RecoveryRepository;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Recovery lifecycle transitions initiated by hand (Phase 8):
 * EXPECTED/INVOICED → RECEIVED via mark-received form, and either
 * state → WRITTEN_OFF via write-off form. The EXPECTED → INVOICED leg
 * is driven automatically by the recoveries bordereau export
 * (Phase 4); this service does not touch that path.
 *
 * <p>Terminal transitions (RECEIVED, WRITTEN_OFF) are one-way — a
 * request against a recovery already in either state returns 409 with a
 * named message. No re-open surface.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecoveryService {

    private static final String ENTITY_TYPE = "Recovery";

    private final RecoveryRepository recoveryRepository;
    private final AuditPublisher auditPublisher;

    public Mono<RecoveryResponse> get(UUID id) {
        return recoveryRepository.findById(id)
                .switchIfEmpty(Mono.error(new IllegalArgumentException(
                        "Recovery not found: " + id)))
                .map(RecoveryResponse::from);
    }

    /**
     * Mark a recovery as RECEIVED. Valid from EXPECTED or INVOICED;
     * either terminal state returns 409.
     */
    @Transactional
    public Mono<RecoveryResponse> markReceived(UUID id, BigDecimal receivedAmount,
                                               OffsetDateTime receivedAt,
                                               String actorId, String actorEmail) {
        if (receivedAmount == null || receivedAmount.signum() < 0) {
            return Mono.error(new IllegalArgumentException(
                    "receivedAmount must be non-negative"));
        }
        OffsetDateTime effectiveReceivedAt = receivedAt != null ? receivedAt : OffsetDateTime.now();
        return recoveryRepository.findById(id)
                .switchIfEmpty(Mono.error(new IllegalArgumentException(
                        "Recovery not found: " + id)))
                .flatMap(existing -> {
                    if (!"EXPECTED".equals(existing.getStatus())
                            && !"INVOICED".equals(existing.getStatus())) {
                        return Mono.error(new IllegalStateException(
                                "Cannot mark received a " + existing.getStatus() + " recovery — "
                                        + "must be EXPECTED or INVOICED"));
                    }
                    Map<String, Object> before = snapshot(existing);
                    existing.setStatus("RECEIVED");
                    existing.setReceivedAmount(receivedAmount);
                    existing.setReceivedAt(effectiveReceivedAt);
                    existing.setUpdatedAt(OffsetDateTime.now());
                    existing.setActorId(parseUuid(actorId));
                    existing.setActorEmail(actorEmail);
                    return recoveryRepository.save(existing)
                            .flatMap(saved -> publishAudit("RECEIVED", saved, before,
                                            snapshot(saved), actorId, actorEmail)
                                    .thenReturn(RecoveryResponse.from(saved)));
                });
    }

    /**
     * Write off a recovery with a mandatory reason. Valid from EXPECTED
     * or INVOICED; RECEIVED / already-WRITTEN_OFF returns 409.
     */
    @Transactional
    public Mono<RecoveryResponse> writeOff(UUID id, String reason,
                                           String actorId, String actorEmail) {
        if (reason == null || reason.isBlank()) {
            return Mono.error(new IllegalArgumentException(
                    "write-off reason is required"));
        }
        return recoveryRepository.findById(id)
                .switchIfEmpty(Mono.error(new IllegalArgumentException(
                        "Recovery not found: " + id)))
                .flatMap(existing -> {
                    if (!"EXPECTED".equals(existing.getStatus())
                            && !"INVOICED".equals(existing.getStatus())) {
                        return Mono.error(new IllegalStateException(
                                "Cannot write off a " + existing.getStatus() + " recovery — "
                                        + "must be EXPECTED or INVOICED"));
                    }
                    Map<String, Object> before = snapshot(existing);
                    existing.setStatus("WRITTEN_OFF");
                    existing.setWriteOffReason(reason);
                    existing.setUpdatedAt(OffsetDateTime.now());
                    existing.setActorId(parseUuid(actorId));
                    existing.setActorEmail(actorEmail);
                    return recoveryRepository.save(existing)
                            .flatMap(saved -> publishAudit("WRITE_OFF", saved, before,
                                            snapshot(saved), actorId, actorEmail)
                                    .thenReturn(RecoveryResponse.from(saved)));
                });
    }

    // ── Audit helpers ──────────────────────────────────────────────────────

    private Map<String, Object> snapshot(Recovery r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cessionId",       r.getCessionId() != null ? r.getCessionId().toString() : null);
        m.put("status",          r.getStatus());
        m.put("expectedAmount",  r.getExpectedAmount() != null ? r.getExpectedAmount().toPlainString() : null);
        m.put("receivedAmount",  r.getReceivedAmount() != null ? r.getReceivedAmount().toPlainString() : null);
        m.put("currencyCode",    r.getCurrencyCode());
        m.put("invoicedAt",      r.getInvoicedAt());
        m.put("receivedAt",      r.getReceivedAt());
        m.put("writeOffReason",  r.getWriteOffReason());
        return m;
    }

    private Mono<Void> publishAudit(String action, Recovery recovery,
                                    Map<String, Object> before, Map<String, Object> after,
                                    String actorId, String actorEmail) {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            String entityName = "Recovery on cession "
                    + (recovery.getCessionId() != null
                            ? recovery.getCessionId().toString().substring(0, 8) : "n/a")
                    + " — " + (recovery.getStatus() != null ? recovery.getStatus() : "?")
                    + " " + (recovery.getReceivedAmount() != null
                            ? recovery.getReceivedAmount().toPlainString()
                            : recovery.getExpectedAmount() != null
                                    ? recovery.getExpectedAmount().toPlainString() : "0")
                    + " " + recovery.getCurrencyCode();
            var event = AuditEvent.create(
                    tenantId != null ? tenantId : "unknown",
                    ENTITY_TYPE,
                    recovery.getId().toString(),
                    entityName,
                    action,
                    actorId != null ? actorId : "system",
                    actorEmail,
                    before, after,
                    diff(before, after),
                    UUID.randomUUID().toString());
            return auditPublisher.publish(event);
        });
    }

    private String[] diff(Map<String, Object> before, Map<String, Object> after) {
        if (before == null || after == null) return new String[0];
        return before.keySet().stream()
                .filter(k -> !Objects.equals(before.get(k), after.get(k)))
                .toArray(String[]::new);
    }

    private UUID parseUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
    }
}
