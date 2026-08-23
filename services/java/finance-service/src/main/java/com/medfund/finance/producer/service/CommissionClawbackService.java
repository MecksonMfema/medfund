package com.medfund.finance.producer.service;

import com.medfund.finance.producer.entity.ClawbackEvent;
import com.medfund.finance.producer.entity.CommissionRateCard;
import com.medfund.finance.producer.entity.CommissionTransaction;
import com.medfund.finance.producer.repository.ClawbackEventRepository;
import com.medfund.finance.producer.repository.CommissionRateCardRepository;
import com.medfund.finance.producer.repository.CommissionTransactionRepository;
import com.medfund.finance.producer.util.ReferenceGenerator;
import com.medfund.finance.util.DbErrors;
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
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Commission clawback engine. Two triggers, one write-path:
 * <ul>
 *   <li><b>MEMBER_LAPSE</b> — fired by {@link com.medfund.finance.producer.consumer.CommissionClawbackConsumer}
 *       on {@code medfund.users.member-lifecycle} events with status ∈
 *       {@code {lapsed, terminated, deactivated}}. Walks the member's ACCRUED /
 *       PAID commission transactions; each whose rate card carries a
 *       {@code clawbackWindowDays} and whose accrual is inside that window
 *       gets a compensating REVERSED row + a {@link ClawbackEvent} with
 *       {@code source=MEMBER_LAPSE}.</li>
 *   <li><b>CONTRIBUTION_REVOKE</b> — fired by {@link com.medfund.finance.producer.consumer.CommissionRevokeConsumer}
 *       on {@code medfund.contributions.revoked}. Finds the single original
 *       accrual for the revoked contribution and reverses it unconditionally
 *       — a revoked contribution should not have paid a commission at all,
 *       so there's no window to check.</li>
 * </ul>
 *
 * <p><b>Idempotency</b> via {@code ux_clawback_by_source} on
 * {@code (source, triggering_event_ref, commission_transaction_id)} — a
 * replayed lifecycle or revoke event bounces off the UNIQUE constraint.
 * DuplicateKeyException is swallowed cleanly.
 *
 * <p><b>Compensating-transaction shape</b>: mirrors the accounting double-entry
 * approach in {@code FacultativeCessionService.void_} — the compensating row
 * is a full {@link CommissionTransaction} with {@code reversalOfTxnId} set to
 * the original, its {@code nativeAmount} negated, status={@code REVERSED},
 * occurredAt={@code now()}. The original's status flips to
 * {@code CLAWED_BACK} (MEMBER_LAPSE) or {@code REVERSED}
 * (CONTRIBUTION_REVOKE) and its {@code reversedByTxnId} points to the
 * compensating row.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommissionClawbackService {

    private static final String COMMISSION_ENTITY = "CommissionTransaction";
    private static final String CLAWBACK_ENTITY   = "ClawbackEvent";

    private final CommissionTransactionRepository commissionTxnRepository;
    private final ClawbackEventRepository clawbackEventRepository;
    private final CommissionRateCardRepository rateCardRepository;
    private final ReferenceGenerator referenceGenerator;
    private final AuditPublisher auditPublisher;

    /**
     * Process a member-lapse event. Walks ACCRUED + PAID commissions for the
     * member; each that (a) has a rate card with a non-null
     * {@code clawbackWindowDays} and (b) was accrued within that window before
     * {@code lapsedAt} gets clawed back. Rows outside the window are left as-is
     * — the commission was already earned per the tenant's rate-card policy.
     */
    @Transactional
    public Flux<ClawbackEvent> processMemberLapse(UUID memberId, Instant lapsedAt,
                                                   String reason, String actorId, String actorEmail) {
        if (memberId == null) return Flux.empty();
        Instant lapseInstant = lapsedAt != null ? lapsedAt : Instant.now();
        return commissionTxnRepository
                .findByMemberIdAndStatusIn(memberId, List.of("ACCRUED", "PAID"))
                .flatMap(txn -> resolveRateCard(txn)
                        .filter(card -> card.getClawbackWindowDays() != null)
                        .filter(card -> withinWindow(txn.getOccurredAt(),
                                lapseInstant, card.getClawbackWindowDays()))
                        .flatMap(card -> clawback(txn, memberId, lapseInstant, reason,
                                "MEMBER_LAPSE", memberId.toString(),
                                "CLAWED_BACK", actorId, actorEmail)));
    }

    /**
     * Process a contribution-revoke event. Finds the single original commission
     * accrual for the revoked contribution and reverses it. The revoked
     * contribution should not have paid commission at all, so there's no
     * window check — every accrual in this state gets reversed.
     */
    @Transactional
    public Mono<ClawbackEvent> processContributionRevoke(UUID contributionId, UUID memberId,
                                                          Instant revokedAt, String reason,
                                                          String actorId, String actorEmail) {
        if (contributionId == null) return Mono.empty();
        Instant occurredAt = revokedAt != null ? revokedAt : Instant.now();
        return commissionTxnRepository
                .findByContributionIdAndReversalOfTxnIdIsNull(contributionId)
                .filter(txn -> !"REVERSED".equals(txn.getStatus())
                        && !"CLAWED_BACK".equals(txn.getStatus()))
                .flatMap(orig -> clawback(orig,
                        memberId != null ? memberId : orig.getMemberId(),
                        occurredAt, reason,
                        "CONTRIBUTION_REVOKE", contributionId.toString(),
                        "REVERSED", actorId, actorEmail));
    }

    /**
     * Insert the compensating REVERSED row, flip the original, and write the
     * clawback_event. Every write emits an AuditEvent with the friendly
     * reference as entityName. Duplicate clawback (replay of the same trigger)
     * bounces off {@code ux_clawback_by_source} and is swallowed cleanly.
     */
    private Mono<ClawbackEvent> clawback(CommissionTransaction original, UUID memberId,
                                          Instant occurredAt, String reason,
                                          String source, String triggerRef,
                                          String originalTerminalStatus,
                                          String actorId, String actorEmail) {
        BigDecimal reversedAmount = original.getNativeAmount().negate();
        OffsetDateTime nowOffset = OffsetDateTime.ofInstant(occurredAt, ZoneOffset.UTC);

        return referenceGenerator.nextCommissionReference()
                .flatMap(compensatingRef -> {
                    CommissionTransaction reversed = new CommissionTransaction();
                    reversed.setReference(compensatingRef);
                    reversed.setProducerId(original.getProducerId());
                    reversed.setContributionId(original.getContributionId());
                    reversed.setMemberId(original.getMemberId());
                    reversed.setInsuranceLine(original.getInsuranceLine());
                    reversed.setRateCardId(original.getRateCardId());
                    reversed.setNativeAmount(reversedAmount);
                    reversed.setNativeCurrency(original.getNativeCurrency());
                    reversed.setContributionAmount(original.getContributionAmount());
                    reversed.setAppliedRatePct(original.getAppliedRatePct());
                    reversed.setStatus("REVERSED");
                    reversed.setReversalOfTxnId(original.getId());
                    reversed.setOccurredAt(nowOffset);
                    reversed.setActorId(parseUuid(actorId));
                    reversed.setActorEmail(actorEmail);
                    return commissionTxnRepository.save(reversed);
                })
                .flatMap(reversed -> {
                    original.setStatus(originalTerminalStatus);
                    original.setReversedByTxnId(reversed.getId());
                    original.setActorId(parseUuid(actorId));
                    original.setActorEmail(actorEmail);
                    return commissionTxnRepository.save(original).thenReturn(reversed);
                })
                .flatMap(reversed -> {
                    ClawbackEvent evt = new ClawbackEvent();
                    evt.setSource(source);
                    evt.setTriggeringEventRef(triggerRef);
                    evt.setMemberId(memberId);
                    evt.setProducerId(original.getProducerId());
                    evt.setCommissionTransactionId(original.getId());
                    evt.setReversalTxnId(reversed.getId());
                    evt.setNativeAmount(original.getNativeAmount());
                    evt.setNativeCurrency(original.getNativeCurrency());
                    evt.setReason(reason);
                    evt.setOccurredAt(nowOffset);
                    evt.setActorId(parseUuid(actorId));
                    evt.setActorEmail(actorEmail);
                    return clawbackEventRepository.save(evt)
                            .onErrorResume(err -> {
                                if (DbErrors.isUniqueViolation(err)) {
                                    log.info("Clawback already recorded for source={} trigger={} txn={} — idempotent skip",
                                            source, triggerRef, original.getId());
                                    return Mono.empty();
                                }
                                return Mono.error(err);
                            });
                })
                .flatMap(savedClawback -> emitAudits(original, savedClawback, actorId, actorEmail)
                        .thenReturn(savedClawback));
    }

    private Mono<CommissionRateCard> resolveRateCard(CommissionTransaction txn) {
        if (txn.getRateCardId() == null) return Mono.empty();
        return rateCardRepository.findById(txn.getRateCardId());
    }

    private boolean withinWindow(OffsetDateTime accrualAt, Instant lapsedAt, int windowDays) {
        if (accrualAt == null || lapsedAt == null) return false;
        Instant deadline = accrualAt.toInstant().plus(windowDays, ChronoUnit.DAYS);
        return !lapsedAt.isAfter(deadline);
    }

    private Mono<Void> emitAudits(CommissionTransaction original, ClawbackEvent clawback,
                                   String actorId, String actorEmail) {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            String tId = tenantId != null ? tenantId : "unknown";
            String actor = actorId != null ? actorId : "system";
            String actorMail = actorEmail != null ? actorEmail : "system@medfund";
            Map<String, Object> commissionPayload = new LinkedHashMap<>();
            commissionPayload.put("status",           original.getStatus());
            commissionPayload.put("reversedByTxnId",  original.getReversedByTxnId() == null
                    ? null : original.getReversedByTxnId().toString());
            commissionPayload.put("clawbackSource",   clawback.getSource());
            commissionPayload.put("clawbackEventId",  clawback.getId().toString());

            var commissionEvent = AuditEvent.create(
                    tId, COMMISSION_ENTITY, original.getId().toString(),
                    original.getReference(), "CLAWBACK", actor, actorMail,
                    null, commissionPayload,
                    new String[]{"status", "reversedByTxnId"},
                    UUID.randomUUID().toString());

            Map<String, Object> clawbackPayload = new LinkedHashMap<>();
            clawbackPayload.put("source",               clawback.getSource());
            clawbackPayload.put("triggeringEventRef",   clawback.getTriggeringEventRef());
            clawbackPayload.put("commissionTxnId",      clawback.getCommissionTransactionId().toString());
            clawbackPayload.put("reversalTxnId",        clawback.getReversalTxnId() == null
                    ? null : clawback.getReversalTxnId().toString());
            clawbackPayload.put("nativeAmount",         clawback.getNativeAmount().toPlainString());
            clawbackPayload.put("nativeCurrency",       clawback.getNativeCurrency());

            var clawbackAudit = AuditEvent.create(
                    tId, CLAWBACK_ENTITY, clawback.getId().toString(),
                    original.getReference(), "CREATE", actor, actorMail,
                    null, clawbackPayload,
                    new String[]{"source", "nativeAmount"},
                    UUID.randomUUID().toString());

            return auditPublisher.publish(commissionEvent)
                    .then(auditPublisher.publish(clawbackAudit));
        });
    }

    private UUID parseUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
    }
}
