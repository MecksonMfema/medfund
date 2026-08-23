package com.medfund.finance.producer.service;

import com.medfund.finance.producer.dto.ContributionPaidEvent;
import com.medfund.finance.producer.entity.CommissionRateCard;
import com.medfund.finance.producer.entity.CommissionTransaction;
import com.medfund.finance.producer.entity.MemberProducerAssignment;
import com.medfund.finance.producer.entity.Producer;
import com.medfund.finance.producer.repository.CommissionRateCardRepository;
import com.medfund.finance.producer.repository.CommissionTransactionRepository;
import com.medfund.finance.producer.repository.MemberProducerAssignmentRepository;
import com.medfund.finance.producer.repository.ProducerRepository;
import com.medfund.finance.producer.util.ReferenceGenerator;
import com.medfund.finance.util.DbErrors;
import com.medfund.rules.fact.ContributionFact;
import com.medfund.rules.fact.RuleResult;
import com.medfund.rules.service.RuleEvaluationService;
import com.medfund.rules.service.TenantRuleLoader;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Commission accrual engine. Fired from {@link com.medfund.finance.producer.consumer.ProducerCommissionConsumer}
 * per {@code medfund.contributions.paid} event. Hybrid model:
 * <ol>
 *   <li>Resolve the member's active producer via
 *       {@link MemberProducerAssignmentRepository#findActiveFor}. A gap
 *       (no active assignment on the paid-at date) is a warn + skip — the
 *       feature explicitly does not auto-successor per parent-plan P14.</li>
 *   <li>Look up the applicable rate card for the insurance line + producer
 *       tier + as-of date. Missing rate card is a hard error (a member with an
 *       assigned producer but no rate card for their line is a tenant
 *       misconfiguration — better to fail loudly than silently under-pay).</li>
 *   <li>Fire the {@code COMMISSION} agenda-gated rules against a
 *       {@link ContributionFact}. Rule outputs are consumed off
 *       {@code fact.getResults()} — each PAY_COMMISSION result carries the
 *       producer id in {@link RuleResult#getCode()} (empty = defer to the
 *       resolved assigned producer) and rateCardId in
 *       {@link RuleResult#getLayerId()} (zero-amount marker = look up the
 *       card; non-zero = the KICKER amount to add on top).</li>
 *   <li>Sum the base (rate-card lookup) + kickers → total commission,
 *       persist one {@link CommissionTransaction} per (contribution, producer,
 *       rate_card) with status={@code ACCRUED}.</li>
 * </ol>
 *
 * <p><b>Idempotency</b>: the partial UNIQUE index
 * {@code ux_commission_txn_source} on {@code (contribution_id, producer_id,
 * COALESCE(rate_card_id, ...))} catches replays. A duplicate insert
 * surfaces as {@link org.springframework.dao.DuplicateKeyException} and is
 * swallowed cleanly — the ack still fires per {@code
 * bug_reactor_kafka_ack_swallow}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommissionCalcService {

    private static final String ENTITY_TYPE = "CommissionTransaction";
    private static final String AGENDA_GROUP = "COMMISSION";

    private final MemberProducerAssignmentRepository assignmentRepository;
    private final CommissionRateCardRepository rateCardRepository;
    private final CommissionTransactionRepository commissionTxnRepository;
    private final ProducerRepository producerRepository;
    private final TenantRuleLoader tenantRuleLoader;
    private final RuleEvaluationService ruleEvaluationService;
    private final ReferenceGenerator referenceGenerator;
    private final AuditPublisher auditPublisher;

    /**
     * Compute + persist a commission for a paid contribution. Silent no-op
     * when the member has no active producer on the paid-at date. Idempotent
     * — replays swallow the DuplicateKeyException.
     */
    @Transactional
    public Mono<CommissionTransaction> processPaidContribution(ContributionPaidEvent event,
                                                                String actorId, String actorEmail) {
        if (event == null || event.contributionId() == null || event.memberId() == null) {
            log.debug("Skipping commission accrual — event missing contribution or member id");
            return Mono.empty();
        }
        if (event.amount() == null || event.amount().signum() <= 0) {
            log.debug("Skipping commission accrual — non-positive amount on contribution {}",
                    event.contributionId());
            return Mono.empty();
        }
        if (event.insuranceLine() == null || event.insuranceLine().isBlank()) {
            log.debug("Skipping commission accrual — missing insuranceLine on contribution {}",
                    event.contributionId());
            return Mono.empty();
        }
        String tenantId = event.tenantId();
        if (tenantId == null || tenantId.isBlank()) {
            log.debug("Skipping commission accrual — missing tenantId on contribution {}",
                    event.contributionId());
            return Mono.empty();
        }
        UUID tenantUuid;
        try {
            tenantUuid = UUID.fromString(tenantId);
        } catch (IllegalArgumentException e) {
            log.warn("Skipping commission accrual — tenantId not a UUID: {}", tenantId);
            return Mono.empty();
        }

        LocalDate paidOnDate = (event.paidAt() != null ? event.paidAt() : OffsetDateTime.now())
                .withOffsetSameInstant(ZoneOffset.UTC)
                .toLocalDate();

        return assignmentRepository.findActiveFor(event.memberId(), paidOnDate)
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("No active producer for member {} at {} — commission skipped",
                            event.memberId(), paidOnDate);
                    return Mono.empty();
                }))
                .flatMap(assignment -> producerRepository.findById(assignment.getProducerId())
                        .switchIfEmpty(Mono.defer(() -> {
                            log.warn("Assignment {} points to missing producer {} — commission skipped",
                                    assignment.getId(), assignment.getProducerId());
                            return Mono.empty();
                        }))
                        .flatMap(producer -> computeAndPersist(event, producer, paidOnDate,
                                tenantUuid, actorId, actorEmail)));
    }

    private Mono<CommissionTransaction> computeAndPersist(ContributionPaidEvent event, Producer producer,
                                                           LocalDate asOf, UUID tenantUuid,
                                                           String actorId, String actorEmail) {
        return rateCardRepository.findApplicable(event.insuranceLine(), producerTier(producer), asOf)
                .switchIfEmpty(Mono.error(new IllegalStateException(
                        "No active commission rate card for insuranceLine=" + event.insuranceLine()
                                + " tier=" + producerTier(producer) + " asOf=" + asOf)))
                .flatMap(card -> fireCommissionRules(tenantUuid, event, producer, card)
                        .flatMap(kickers -> {
                            BigDecimal base = event.amount()
                                    .multiply(card.getBaseRatePct()).movePointLeft(2);
                            BigDecimal total = base.add(kickers);
                            if (total.signum() <= 0) {
                                log.debug("Commission for contribution {} computes to <= 0 — skipping",
                                        event.contributionId());
                                return Mono.empty();
                            }
                            return referenceGenerator.nextCommissionReference()
                                    .flatMap(ref -> persist(event, producer, card, base, total, ref,
                                            actorId, actorEmail));
                        }));
    }

    /**
     * Fire the {@code COMMISSION} agenda group against a {@link ContributionFact}
     * built from the paid event. Returns the sum of all PAY_COMMISSION result
     * amounts (kickers) — the base rate is applied separately by the caller
     * so the DRL layer only expresses kickers on top.
     */
    private Mono<BigDecimal> fireCommissionRules(UUID tenantUuid, ContributionPaidEvent event,
                                                  Producer producer, CommissionRateCard card) {
        ContributionFact fact = new ContributionFact();
        fact.setContributionId(event.contributionId().toString());
        fact.setMemberId(event.memberId().toString());
        fact.setCurrencyCode(event.currencyCode());
        fact.setPremiumAmount(event.amount());
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("insuranceLine", event.insuranceLine());
        attrs.put("producerId",    producer.getId().toString());
        attrs.put("producerTier",  producerTier(producer));
        attrs.put("rateCardId",    card.getId().toString());
        fact.setAttributes(attrs);

        return tenantRuleLoader.ensureLoaded(tenantUuid)
                .then(ruleEvaluationService.evaluateInGroup(tenantUuid.toString(), AGENDA_GROUP, fact))
                .map(results -> {
                    // The evaluator's returned list is sourced from ClaimFact only
                    // per its contract — for ContributionFact the outputs live on
                    // fact.getResults(). Read those directly.
                    List<RuleResult> factResults = fact.getResults();
                    BigDecimal sum = BigDecimal.ZERO;
                    for (RuleResult r : factResults) {
                        if (!"PAY_COMMISSION".equals(r.getType())) continue;
                        if (r.getAdjustedAmount() == null) continue;
                        // Only sum kicker amounts (non-zero). RATE_CARD markers
                        // are zero-amount and are covered by the base-rate calc
                        // in the caller.
                        if (r.getAdjustedAmount().signum() != 0) {
                            sum = sum.add(r.getAdjustedAmount());
                        }
                    }
                    return sum;
                });
    }

    private Mono<CommissionTransaction> persist(ContributionPaidEvent event, Producer producer,
                                                 CommissionRateCard card, BigDecimal base,
                                                 BigDecimal total, String reference,
                                                 String actorId, String actorEmail) {
        CommissionTransaction txn = new CommissionTransaction();
        txn.setReference(reference);
        txn.setProducerId(producer.getId());
        txn.setContributionId(event.contributionId());
        txn.setMemberId(event.memberId());
        txn.setInsuranceLine(event.insuranceLine());
        txn.setRateCardId(card.getId());
        txn.setNativeAmount(total);
        txn.setNativeCurrency(event.currencyCode());
        txn.setContributionAmount(event.amount());
        txn.setAppliedRatePct(card.getBaseRatePct());
        txn.setStatus("ACCRUED");
        txn.setOccurredAt(event.paidAt() != null ? event.paidAt() : OffsetDateTime.now());
        txn.setActorId(parseUuid(actorId));
        txn.setActorEmail(actorEmail);

        return commissionTxnRepository.save(txn)
                .onErrorResume(err -> {
                    if (DbErrors.isUniqueViolation(err)) {
                        log.info("Commission already exists for contribution {} producer {} — idempotent skip",
                                event.contributionId(), producer.getId());
                        return Mono.empty();
                    }
                    return Mono.error(err);
                })
                .flatMap(saved -> emitAudit(saved, base, actorId, actorEmail).thenReturn(saved));
    }

    /**
     * Producer tiering — MVP: presence of parent → SUB, absence → DIRECT.
     * Real tenant tiering (BRONZE / SILVER / GOLD, etc.) lives on
     * {@code commission_rate_card.producer_tier} and is looked up via
     * {@link CommissionRateCardRepository#findApplicable}. The MVP mapping is
     * good enough for the "sub-broker share" pattern out of the box; a
     * follow-up ticket adds a producer.tier field.
     */
    private String producerTier(Producer p) {
        return p.getParentProducerId() == null ? "DIRECT" : "SUB";
    }

    private Mono<Void> emitAudit(CommissionTransaction txn, BigDecimal base,
                                  String actorId, String actorEmail) {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            Map<String, Object> newValue = new LinkedHashMap<>();
            newValue.put("producerId",       txn.getProducerId().toString());
            newValue.put("contributionId",   txn.getContributionId().toString());
            newValue.put("memberId",         txn.getMemberId().toString());
            newValue.put("insuranceLine",    txn.getInsuranceLine());
            newValue.put("rateCardId",       txn.getRateCardId() == null ? null : txn.getRateCardId().toString());
            newValue.put("baseAmount",       base.toPlainString());
            newValue.put("nativeAmount",     txn.getNativeAmount().toPlainString());
            newValue.put("nativeCurrency",   txn.getNativeCurrency());
            newValue.put("appliedRatePct",   txn.getAppliedRatePct().toPlainString());
            newValue.put("status",           txn.getStatus());
            var event = AuditEvent.create(
                    tenantId != null ? tenantId : "unknown",
                    ENTITY_TYPE,
                    txn.getId().toString(),
                    txn.getReference(),               // friendly per feedback_audit_entity_name
                    "CREATE",
                    actorId != null ? actorId : "system",
                    actorEmail != null ? actorEmail : "system@medfund",
                    null, newValue,
                    new String[]{"nativeAmount", "status"},
                    UUID.randomUUID().toString());
            return auditPublisher.publish(event);
        });
    }

    private UUID parseUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
    }
}
