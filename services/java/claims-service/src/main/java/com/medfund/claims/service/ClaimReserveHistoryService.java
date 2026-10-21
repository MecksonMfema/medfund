package com.medfund.claims.service;

import com.medfund.claims.entity.Claim;
import com.medfund.claims.entity.ClaimReserveHistory;
import com.medfund.claims.exception.ClaimNotFoundException;
import com.medfund.claims.repository.ClaimRepository;
import com.medfund.claims.repository.ClaimReserveHistoryRepository;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Case-reserve history for {@link Claim}s (Phase 14 §A actuarial IBNR).
 *
 * <p>Rows are append-only in intent — each call to {@link #set} inserts a
 * fresh row rather than updating an existing one, so the incurred-triangle
 * pipeline can reconstruct the reserve as-of any past date.
 *
 * <p>{@link #autoZero} is called from {@link ClaimService} when a claim
 * transitions to {@code REJECTED} or {@code CANCELLED} — the incurred
 * triangle must stop summing residual reserve after close, so we write a
 * zero-reserve row with a canonical reason string.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClaimReserveHistoryService {

    private static final String ENTITY_TYPE = "CLAIM_RESERVE_HISTORY";
    private static final int MIN_REASON_LENGTH = 5;

    private final ClaimReserveHistoryRepository repository;
    private final ClaimRepository claimRepository;
    private final AuditPublisher auditPublisher;

    @Transactional
    public Mono<ClaimReserveHistory> set(UUID claimId, BigDecimal reservedAmount, String reasonNote,
                                         String actorId, String actorEmail) {
        if (reservedAmount == null) {
            return Mono.error(new IllegalArgumentException("reservedAmount is required"));
        }
        if (reservedAmount.signum() < 0) {
            return Mono.error(new IllegalArgumentException("reservedAmount must be >= 0"));
        }
        if (reasonNote == null || reasonNote.trim().length() < MIN_REASON_LENGTH) {
            return Mono.error(new IllegalArgumentException(
                    "reasonNote must be at least " + MIN_REASON_LENGTH + " characters"));
        }
        String trimmedNote = reasonNote.trim();
        return claimRepository.findById(claimId)
                .switchIfEmpty(Mono.error(new ClaimNotFoundException(claimId)))
                .flatMap(claim -> insertRow(claim, reservedAmount, trimmedNote, actorId, actorEmail));
    }

    public Flux<ClaimReserveHistory> history(UUID claimId) {
        return repository.findByClaimIdOrderByEffectiveAtDesc(claimId);
    }

    /**
     * Write a zero-reserve row when a claim closes to REJECTED or CANCELLED.
     * Never blocks the caller — errors are logged and swallowed so a broken
     * audit write must not roll back the claim's state transition.
     */
    public Mono<Void> autoZero(UUID claimId, String terminalStatus, String actorId, String actorEmail) {
        return set(claimId, BigDecimal.ZERO, "Auto-zero: claim " + terminalStatus, actorId, actorEmail)
                .doOnError(e -> log.warn("Reserve auto-zero failed for claim {} → {}: {}",
                        claimId, terminalStatus, e.getMessage()))
                .onErrorResume(e -> Mono.empty())
                .then();
    }

    private Mono<ClaimReserveHistory> insertRow(Claim claim, BigDecimal reservedAmount, String reasonNote,
                                                String actorId, String actorEmail) {
        var row = new ClaimReserveHistory();
        row.setClaimId(claim.getId());
        row.setReservedAmount(reservedAmount);
        row.setEffectiveAt(OffsetDateTime.now());
        row.setActorId(parseUuid(actorId));
        row.setActorEmail(actorEmail);
        row.setReasonNote(reasonNote);
        return repository.save(row)
                .flatMap(saved -> publishAudit(claim, saved, actorId, actorEmail).thenReturn(saved));
    }

    private Mono<Void> publishAudit(Claim claim, ClaimReserveHistory row,
                                    String actorId, String actorEmail) {
        Map<String, Object> newValue = new LinkedHashMap<>();
        newValue.put("claimId", claim.getId().toString());
        newValue.put("claimNumber", claim.getClaimNumber());
        newValue.put("reservedAmount", row.getReservedAmount().toPlainString());
        newValue.put("reasonNote", row.getReasonNote());
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            AuditEvent event = AuditEvent.create(
                    tenantId != null ? tenantId : "unknown",
                    ENTITY_TYPE,
                    row.getId().toString(),
                    "reserve:" + claim.getClaimNumber(),
                    "CREATE",
                    actorId,
                    actorEmail,
                    null,
                    newValue,
                    new String[]{"reservedAmount"},
                    UUID.randomUUID().toString()
            );
            return auditPublisher.publish(event);
        });
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
