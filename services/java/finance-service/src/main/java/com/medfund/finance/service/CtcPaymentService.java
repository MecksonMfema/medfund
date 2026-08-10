package com.medfund.finance.service;

import com.medfund.finance.client.FxConverter;
import com.medfund.finance.dto.CtcPaymentDtos.CreateCtcPaymentRequest;
import com.medfund.finance.dto.CtcPaymentDtos.ReverseCtcPaymentRequest;
import com.medfund.finance.dto.CtcPaymentFilterParams;
import com.medfund.finance.dto.CtcPaymentRow;
import com.medfund.finance.dto.PageResponse;
import com.medfund.finance.entity.CtcPayment;
import com.medfund.finance.entity.MemberPayable;
import com.medfund.finance.entity.MemberPayableApplication;
import com.medfund.finance.repository.CtcPaymentQueryRepository;
import com.medfund.finance.repository.CtcPaymentRepository;
import com.medfund.finance.repository.MemberPayableApplicationRepository;
import com.medfund.finance.repository.MemberPayableBalanceRepository;
import com.medfund.finance.repository.MemberPayableRepository;
import com.medfund.finance.util.Actors;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Core mechanic for Claims-to-Contributions (CTC) transfers. Every CTC
 * links to a {@link MemberPayable} (the source of the credit), and every
 * commit posts a positive {@link MemberPayableApplication} row while
 * publishing a {@code medfund.finance.ctc.committed} event so
 * contributions-service can post the matching {@code CTC_OFFSET}
 * transaction against the member's contribution ledger.
 *
 * <p>Reversal follows the same compensating-row pattern as advance
 * payments: the original CTC is marked {@code status=reversed} and a
 * fresh {@code type=REVERSAL, status=committed} row is written that
 * points back via {@code reverses_ctc_id}. A negating application row
 * (with {@code amount_applied = -original.amount}) returns the
 * payable's consumption to its pre-CTC state, and a
 * {@code medfund.finance.ctc.reversed} event triggers a
 * {@code CTC_OFFSET_REVERSAL} transaction downstream.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CtcPaymentService {

    private final CtcPaymentRepository repository;
    private final CtcPaymentQueryRepository queryRepository;
    private final MemberPayableRepository memberPayableRepository;
    private final MemberPayableApplicationRepository applicationRepository;
    private final MemberPayableBalanceRepository balanceRepository;
    private final MemberBalanceService memberBalanceService;
    private final AuditPublisher auditPublisher;
    private final FinanceEventPublisher eventPublisher;
    private final FxConverter fxConverter;
    private final DatabaseClient db;

    public Flux<CtcPayment> findAll() {
        return repository.findAllOrdered();
    }

    public Flux<CtcPayment> findByCommitted(boolean committed) {
        return repository.findByCommitted(committed);
    }

    /**
     * Server-side paginated CTC list. Joins {@code members} and
     * {@code groups} so the client renders the beneficiary chip inline —
     * the previous unpaginated flow surfaced raw UUIDs and was hostile
     * at scale.
     */
    public Mono<PageResponse<CtcPaymentRow>> searchPaged(CtcPaymentFilterParams params) {
        int page = Math.max(params.page(), 0);
        int size = Math.min(Math.max(params.size(), 1), 200);
        int offset = page * size;
        return queryRepository.search(params, size, offset)
                .collectList()
                .zipWith(queryRepository.count(params))
                .map(tuple -> PageResponse.of(tuple.getT1(), tuple.getT2(), page, size));
    }

    public Mono<CtcPayment> findById(UUID id) {
        return repository.findById(id)
            .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,
                "CTC payment not found: " + id)));
    }

    @Transactional
    public Mono<CtcPayment> create(CreateCtcPaymentRequest request, String actor, String actorEmail) {
        // Member-only for MVP — see design decision #1 in the plan.
        if (request.memberId() == null) {
            return Mono.error(new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "memberId is required — group-only CTC is out of scope for this release"));
        }
        if (request.groupId() != null) {
            return Mono.error(new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "Group CTC is out of scope for this release — omit groupId"));
        }
        return memberPayableRepository.findById(request.memberPayableId())
            .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "Member-payable not found: " + request.memberPayableId())))
            .flatMap(payable -> {
                if (!payable.getMemberId().equals(request.memberId())) {
                    return Mono.error(new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Member-payable belongs to a different member"));
                }
                if (!"open".equals(payable.getStatus())) {
                    return Mono.error(new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Member-payable status is " + payable.getStatus() + " — cannot offset"));
                }
                return Mono.deferContextual(ctx -> {
                    UUID tenantId = parseTenant(TenantContext.get(ctx));
                    return convertIfNeeded(request.amount(), request.currencyCode(),
                                           payable.getCurrencyCode(), tenantId)
                        .flatMap(convertedAmount -> balanceRepository.remainingOn(payable.getId())
                            .flatMap(remaining -> {
                                if (convertedAmount.compareTo(remaining) > 0) {
                                    return Mono.<CtcPayment>error(new ResponseStatusException(
                                        HttpStatus.UNPROCESSABLE_ENTITY,
                                        "CTC amount " + convertedAmount + " " + payable.getCurrencyCode()
                                        + " exceeds remaining payable " + remaining));
                                }
                                return saveDraftCtc(request, actor, actorEmail);
                            }));
                });
            });
    }

    private Mono<CtcPayment> saveDraftCtc(CreateCtcPaymentRequest request, String actor, String actorEmail) {
        var entity = new CtcPayment();
        entity.setGroupId(request.groupId());
        entity.setMemberId(request.memberId());
        entity.setMemberPayableId(request.memberPayableId());
        entity.setAmount(request.amount());
        entity.setCurrencyCode(request.currencyCode());
        entity.setContributionId(request.contributionId());
        entity.setType("CTC");
        entity.setStatus("draft");
        entity.setCommitted(false);
        entity.setCreatedBy(Actors.parseId(actor));
        return repository.save(entity)
            .flatMap(saved -> publishAudit("CREATE", saved, null, snapshot(saved), actor, actorEmail)
                .thenReturn(saved));
    }

    @Transactional
    public Mono<CtcPayment> commit(UUID id, String actor, String actorEmail) {
        return repository.findById(id)
            .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,
                "CTC payment not found: " + id)))
            .flatMap(existing -> {
                if ("committed".equals(existing.getStatus())
                        || Boolean.TRUE.equals(existing.getCommitted())) {
                    // Idempotent — no state change, no audit, no event.
                    return Mono.just(existing);
                }
                if (existing.getStatus() != null && !"draft".equals(existing.getStatus())) {
                    return Mono.error(new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Cannot commit CTC in status " + existing.getStatus()));
                }
                Map<String, Object> before = snapshot(existing);
                UUID actorId = Actors.parseId(actor);
                existing.setStatus("committed");
                existing.setCommitted(true);
                existing.setCommittedAt(Instant.now());
                existing.setCommittedBy(actorId);
                return repository.save(existing)
                    .flatMap(saved -> writeApplication(saved, actorId)
                        .flatMap(app -> eventPublisher.publishCtcApplied(app).thenReturn(saved))
                        .then(maybeMarkPayableApplied(saved.getMemberPayableId()))
                        .then(memberBalanceService.updateBalance(
                                saved.getMemberId(), saved.getCurrencyCode(),
                                null, null, saved.getAmount(),
                                actor, actorEmail).then())
                        .then(publishAudit("UPDATE", saved, before, snapshot(saved), actor, actorEmail))
                        .then(Mono.deferContextual(ctx ->
                            eventPublisher.publishCtcCommitted(saved, TenantContext.get(ctx))))
                        .thenReturn(saved));
            });
    }

    @Transactional
    public Mono<CtcPayment> reverse(UUID id, ReverseCtcPaymentRequest req,
                                    String actor, String actorEmail) {
        return repository.findById(id)
            .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,
                "CTC payment not found: " + id)))
            .flatMap(original -> {
                if (!"committed".equals(original.getStatus())) {
                    return Mono.error(new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Cannot reverse CTC in status " + original.getStatus()));
                }
                if ("REVERSAL".equals(original.getType())) {
                    return Mono.error(new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Cannot reverse a compensating REVERSAL row"));
                }
                Instant now = Instant.now();
                UUID actorId = Actors.parseId(actor);
                Map<String, Object> beforeOriginal = snapshot(original);
                original.setStatus("reversed");

                CtcPayment compensating = new CtcPayment();
                compensating.setType("REVERSAL");
                compensating.setStatus("committed");
                compensating.setReversesCtcId(original.getId());
                compensating.setGroupId(original.getGroupId());
                compensating.setMemberId(original.getMemberId());
                compensating.setMemberPayableId(original.getMemberPayableId());
                compensating.setAmount(original.getAmount());
                compensating.setCurrencyCode(original.getCurrencyCode());
                compensating.setContributionId(original.getContributionId());
                compensating.setCommitted(true);
                compensating.setCommittedAt(now);
                compensating.setCommittedBy(actorId);
                compensating.setCreatedBy(actorId);

                return repository.save(original)
                    .flatMap(savedOriginal -> repository.save(compensating)
                        .flatMap(savedCompensating -> writeNegatingApplication(savedOriginal,
                                    savedCompensating, actorId)
                            .flatMap(app -> eventPublisher.publishCtcApplied(app)
                                    .thenReturn(savedCompensating))
                            .then(reopenPayable(savedOriginal.getMemberPayableId()))
                            .then(memberBalanceService.updateBalance(
                                    savedOriginal.getMemberId(), savedOriginal.getCurrencyCode(),
                                    null, null, savedOriginal.getAmount().negate(),
                                    actor, actorEmail).then())
                            .then(publishAudit("UPDATE", savedOriginal, beforeOriginal,
                                    snapshot(savedOriginal), actor, actorEmail))
                            .then(publishAudit("REVERSE", savedCompensating, null,
                                    snapshot(savedCompensating), actor, actorEmail))
                            .then(Mono.deferContextual(ctx -> eventPublisher.publishCtcReversed(
                                    savedOriginal, savedCompensating,
                                    req != null ? req.reason() : null,
                                    TenantContext.get(ctx))))
                            .thenReturn(savedCompensating)));
            });
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private Mono<BigDecimal> convertIfNeeded(BigDecimal amount, String fromCurrency,
                                              String toCurrency, UUID tenantId) {
        if (fromCurrency == null || toCurrency == null || fromCurrency.equalsIgnoreCase(toCurrency)) {
            return Mono.just(amount);
        }
        return fxConverter.convert(amount, fromCurrency, toCurrency, LocalDate.now(), tenantId);
    }

    /**
     * Positive application row — the CTC consumes {@code amount} of the
     * payable's remaining balance. Currency mismatch between the CTC and
     * the payable is preserved on the application row's own
     * {@code currency_code} column; the derived-aggregate balance query
     * groups by currency so a mixed-currency application is legal but
     * unusual.
     */
    private Mono<MemberPayableApplication> writeApplication(CtcPayment ctc, UUID actorId) {
        MemberPayableApplication app = new MemberPayableApplication();
        app.setMemberPayableId(ctc.getMemberPayableId());
        app.setSourceType("CTC");
        app.setSourceId(ctc.getId());
        app.setAmountApplied(ctc.getAmount());
        app.setCurrencyCode(ctc.getCurrencyCode());
        app.setAppliedAt(Instant.now());
        app.setAppliedBy(actorId);
        return applicationRepository.save(app);
    }

    /**
     * Negative application row — the reversal returns the payable's
     * consumption to its pre-CTC state. {@code source_id} points at the
     * REVERSAL row (not the original) so the audit trail links back to
     * the compensating entry.
     */
    private Mono<MemberPayableApplication> writeNegatingApplication(CtcPayment original,
                                                                     CtcPayment compensating,
                                                                     UUID actorId) {
        MemberPayableApplication app = new MemberPayableApplication();
        app.setMemberPayableId(original.getMemberPayableId());
        app.setSourceType("CTC");
        app.setSourceId(compensating.getId());
        app.setAmountApplied(original.getAmount().negate());
        app.setCurrencyCode(original.getCurrencyCode());
        app.setAppliedAt(Instant.now());
        app.setAppliedBy(actorId);
        return applicationRepository.save(app);
    }

    /**
     * If a payable is fully consumed after a commit, mark it
     * {@code status=applied}. Idempotent — a repeat call against an
     * already-applied payable is a no-op.
     */
    private Mono<Void> maybeMarkPayableApplied(UUID payableId) {
        if (payableId == null) return Mono.empty();
        return balanceRepository.remainingOn(payableId)
            .flatMap(remaining -> {
                if (remaining.signum() > 0) return Mono.empty();
                return db.sql("""
                        UPDATE member_payables
                           SET status = 'applied'
                         WHERE id     = :id
                           AND status = 'open'
                        """)
                    .bind("id", payableId)
                    .fetch()
                    .rowsUpdated()
                    .then();
            });
    }

    /**
     * A reversal always reopens the payable if it had been fully consumed —
     * the negating application row put a spendable balance back on it.
     * Idempotent — reopening an already-open payable is a no-op.
     */
    private Mono<Void> reopenPayable(UUID payableId) {
        if (payableId == null) return Mono.empty();
        return db.sql("""
                UPDATE member_payables
                   SET status = 'open'
                 WHERE id     = :id
                   AND status = 'applied'
                """)
            .bind("id", payableId)
            .fetch()
            .rowsUpdated()
            .then();
    }

    private static UUID parseTenant(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return UUID.fromString(raw); } catch (IllegalArgumentException e) { return null; }
    }

    private Map<String, Object> snapshot(CtcPayment c) {
        Map<String, Object> snap = new HashMap<>();
        snap.put("amount", c.getAmount() != null ? c.getAmount().toPlainString() : null);
        snap.put("currencyCode", c.getCurrencyCode());
        snap.put("groupId", c.getGroupId() != null ? c.getGroupId().toString() : null);
        snap.put("memberId", c.getMemberId() != null ? c.getMemberId().toString() : null);
        snap.put("memberPayableId",
                c.getMemberPayableId() != null ? c.getMemberPayableId().toString() : null);
        snap.put("reversesCtcId",
                c.getReversesCtcId() != null ? c.getReversesCtcId().toString() : null);
        snap.put("type", c.getType());
        snap.put("status", c.getStatus());
        snap.put("committed", c.getCommitted());
        return snap;
    }

    private Mono<Void> publishAudit(String action, CtcPayment c, Map<String, Object> before,
                                     Map<String, Object> after, String actor, String actorEmail) {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            String entityName = "CTC " + (c.getAmount() != null ? c.getAmount().toPlainString() : "")
                              + " " + (c.getCurrencyCode() != null ? c.getCurrencyCode() : "");
            var event = AuditEvent.create(
                tenantId != null ? tenantId : "unknown",
                "CtcPayment",
                c.getId().toString(),
                entityName.trim(),
                action,
                actor != null ? actor : "system",
                actorEmail,
                before,
                after,
                new String[]{"status"},
                UUID.randomUUID().toString()
            );
            return auditPublisher.publish(event);
        });
    }
}
