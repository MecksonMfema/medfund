package com.medfund.finance.reinsurance.service;

import com.medfund.finance.reinsurance.dto.ContributionPaidEvent;
import com.medfund.finance.reinsurance.entity.Cession;
import com.medfund.finance.reinsurance.entity.Treaty;
import com.medfund.finance.reinsurance.repository.CessionRepository;
import com.medfund.finance.reinsurance.repository.TreatyRepository;
import com.medfund.finance.util.DbErrors;
import com.medfund.rules.fact.ClaimFact;
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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Premium cession dispatch. Siblings {@link CessionService} but keys off
 * the {@code medfund.contributions.paid} event rather than an adjudicated
 * claim. For each ACTIVE <em>proportional</em> treaty (QUOTA_SHARE /
 * SURPLUS_SHARE) covering the paying member's scheme line, focuses the
 * REINSURANCE rules-engine agenda group and writes one PREMIUM Cession
 * per fired {@code CEDE_TO_TREATY} rule.
 *
 * <p><b>Non-proportional (XoL / StopLoss) treaties are excluded here</b> —
 * they take a flat inception premium via
 * {@link com.medfund.finance.reinsurance.job.ReinsuranceTreatyPremiumExecutor},
 * not a per-contribution cede.
 *
 * <p><b>No Recovery is written</b>. Recoveries model money owed by the
 * reinsurer back to the fund for its share of a loss; premium cessions
 * are money the fund owes the reinsurer, tracked directly on the Cession
 * row (see the recoveries bordereau vs cession bordereau split).
 *
 * <p>Idempotency: UNIQUE {@code ux_cession_source_event} on (treaty_id,
 * source_event_id, cession_type) — a re-delivered contribution-paid event
 * writes zero duplicate rows. The friendly path is the existence read
 * before insert; the belt-and-braces path is the DB constraint.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PremiumCessionService {

    private static final String CESSION_ENTITY_TYPE = "Cession";
    private static final String AGENDA_GROUP = "REINSURANCE";
    private static final Set<String> PROPORTIONAL_TYPES = Set.of("QUOTA_SHARE", "SURPLUS_SHARE");

    private final CessionRepository cessionRepository;
    private final TreatyRepository treatyRepository;
    private final TenantRuleLoader tenantRuleLoader;
    private final RuleEvaluationService ruleEvaluationService;
    private final AuditPublisher auditPublisher;

    /**
     * Called by the premium-cession consumer per paid contribution. For
     * each ACTIVE proportional treaty covering {@code event.insuranceLine()},
     * fires the REINSURANCE agenda group and writes one PREMIUM Cession
     * per {@code CEDE_TO_TREATY} result whose treaty id matches.
     */
    @Transactional
    public Flux<Cession> processPaidContribution(ContributionPaidEvent event,
                                                 String actorId, String actorEmail) {
        if (event.insuranceLine() == null || event.amount() == null
                || event.amount().signum() <= 0) {
            log.debug("Skipping premium cession — missing line or non-positive amount ({})",
                    event.contributionId());
            return Flux.empty();
        }
        String tenantId = event.tenantId();
        if (tenantId == null || tenantId.isBlank()) {
            log.debug("Skipping premium cession — no tenant id on event {}", event.contributionId());
            return Flux.empty();
        }
        UUID tenantUuid;
        try {
            tenantUuid = UUID.fromString(tenantId);
        } catch (IllegalArgumentException e) {
            log.warn("Skipping premium cession — tenant id not a UUID: {}", tenantId);
            return Flux.empty();
        }

        return treatyRepository.findActiveByInsuranceLine(event.insuranceLine())
                .filter(t -> PROPORTIONAL_TYPES.contains(t.getTreatyType()))
                .collectList()
                .flatMapMany(treaties -> {
                    if (treaties.isEmpty()) {
                        log.debug("No ACTIVE proportional treaties for line={} — skipping premium cession",
                                event.insuranceLine());
                        return Flux.empty();
                    }
                    return tenantRuleLoader.ensureLoaded(tenantUuid)
                            .thenMany(fireCessionRules(tenantId, event))
                            .flatMap(result -> matchTreaty(treaties, result)
                                    .flatMap(treaty -> writeCession(treaty, result, event,
                                            actorId, actorEmail))
                                    .flux());
                });
    }

    /**
     * Fires the REINSURANCE agenda-gated rules once with a synthesised
     * {@link ClaimFact} carrying the contribution's amount, so rules
     * authored against {@code claim.amount} work identically for premium.
     * The CEDE_TO_TREATY emitter reads {@code $claim.getAmount()} — see
     * {@link com.medfund.rules.compiler.CedeToTreatyEmitter} for why it
     * always binds ClaimFact regardless of the trigger source.
     */
    private Flux<RuleResult> fireCessionRules(String tenantId, ContributionPaidEvent event) {
        ClaimFact fact = new ClaimFact();
        fact.setClaimId(event.contributionId() != null ? event.contributionId().toString() : null);
        fact.setMemberId(event.memberId() != null ? event.memberId().toString() : null);
        fact.setAmount(event.amount());
        fact.setCurrencyCode(event.currencyCode());
        return ruleEvaluationService.evaluateInGroup(tenantId, AGENDA_GROUP, fact)
                .flatMapMany(Flux::fromIterable)
                .filter(r -> "CEDE_TO_TREATY".equals(r.getType())
                        && r.getCode() != null && !r.getCode().isBlank()
                        && r.getAdjustedAmount() != null && r.getAdjustedAmount().signum() > 0);
    }

    private Mono<Treaty> matchTreaty(List<Treaty> treaties, RuleResult result) {
        UUID treatyId;
        try {
            treatyId = UUID.fromString(result.getCode());
        } catch (IllegalArgumentException e) {
            log.warn("CEDE_TO_TREATY rule emitted non-UUID treaty code '{}' — skipping",
                    result.getCode());
            return Mono.empty();
        }
        return treaties.stream()
                .filter(t -> treatyId.equals(t.getId()))
                .findFirst()
                .map(Mono::just)
                .orElseGet(() -> {
                    log.warn("CEDE_TO_TREATY targeted treaty {} that is not an ACTIVE proportional treaty for this line — skipping",
                            treatyId);
                    return Mono.empty();
                });
    }

    private Mono<Cession> writeCession(Treaty treaty, RuleResult result,
                                       ContributionPaidEvent event,
                                       String actorId, String actorEmail) {
        // Friendly-path idempotency: read before write so a re-delivered
        // event logs a clean "already ceded" instead of pushing through
        // to the UNIQUE-violation belt-and-braces catch below.
        return cessionRepository.findByTreatyIdAndSourceEventIdAndCessionType(
                        treaty.getId(), event.contributionId(), "PREMIUM")
                .hasElement()
                .flatMap(exists -> {
                    if (Boolean.TRUE.equals(exists)) {
                        log.debug("Premium cession already exists for treaty={} contribution={} — idempotent skip",
                                treaty.getId(), event.contributionId());
                        return Mono.empty();
                    }
                    return insertCession(treaty, result, event, actorId, actorEmail);
                });
    }

    private Mono<Cession> insertCession(Treaty treaty, RuleResult result,
                                        ContributionPaidEvent event,
                                        String actorId, String actorEmail) {
        Cession c = new Cession();
        c.setTreatyId(treaty.getId());
        // Proportional treaties have no layer — layerId is null for QS/SS.
        c.setTreatyLayerId(null);
        c.setCessionType("PREMIUM");
        c.setSource("AUTOMATIC");
        c.setStatus("ACTIVE");
        c.setSourceEventId(event.contributionId());
        c.setSourceEventType("CONTRIBUTION_PAID");
        c.setBasisAmount(event.amount());
        c.setCededAmount(result.getAdjustedAmount());
        c.setCurrencyCode(event.currencyCode());
        c.setOccurredAt(event.paidAt() != null ? event.paidAt() : OffsetDateTime.now());
        c.setActorId(parseUuid(actorId));
        c.setActorEmail(actorEmail);

        return cessionRepository.save(c)
                .onErrorResume(err -> {
                    if (DbErrors.isUniqueViolation(err)) {
                        log.info("Premium cession UNIQUE race — treaty={} contribution={} — idempotent skip",
                                treaty.getId(), event.contributionId());
                        return Mono.empty();
                    }
                    return Mono.error(err);
                })
                .flatMap(saved -> publishCessionAudit(saved, treaty, actorId, actorEmail)
                        .thenReturn(saved));
    }

    private Mono<Void> publishCessionAudit(Cession cession, Treaty treaty,
                                           String actorId, String actorEmail) {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            String entityName = "Premium cession on treaty " + treaty.getTreatyRef()
                    + " — " + cession.getCededAmount().toPlainString()
                    + " " + cession.getCurrencyCode();
            Map<String, Object> newValue = new LinkedHashMap<>();
            newValue.put("treatyId",         cession.getTreatyId().toString());
            newValue.put("cessionType",      cession.getCessionType());
            newValue.put("source",           cession.getSource());
            newValue.put("status",           cession.getStatus());
            newValue.put("sourceEventId",    cession.getSourceEventId().toString());
            newValue.put("sourceEventType",  cession.getSourceEventType());
            newValue.put("basisAmount",      cession.getBasisAmount().toPlainString());
            newValue.put("cededAmount",      cession.getCededAmount().toPlainString());
            newValue.put("currencyCode",     cession.getCurrencyCode());
            var event = AuditEvent.create(
                    tenantId != null ? tenantId : "unknown",
                    CESSION_ENTITY_TYPE,
                    cession.getId().toString(),
                    entityName,
                    "CREATE",
                    actorId != null ? actorId : "system",
                    actorEmail,
                    null, newValue,
                    new String[]{"basisAmount", "cededAmount", "status"},
                    UUID.randomUUID().toString());
            return auditPublisher.publish(event);
        });
    }

    private UUID parseUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
    }
}
