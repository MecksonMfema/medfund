package com.medfund.finance.reinsurance.service;

import com.medfund.finance.reinsurance.dto.ClaimAdjudicatedEvent;
import com.medfund.finance.reinsurance.entity.Cession;
import com.medfund.finance.reinsurance.entity.Recovery;
import com.medfund.finance.reinsurance.entity.Treaty;
import com.medfund.finance.reinsurance.repository.CessionRepository;
import com.medfund.finance.reinsurance.repository.RecoveryRepository;
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

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Loss cession dispatch. For each ACTIVE treaty covering the claim's
 * insurance line, focuses the REINSURANCE rules-engine agenda group,
 * reads back {@code CEDE_TO_TREATY} results, and writes one Cession +
 * one EXPECTED Recovery per fired rule.
 *
 * <p>Idempotency is enforced two ways: (1) an existence check via
 * {@link CessionRepository#findByTreatyIdAndSourceEventIdAndCessionType}
 * — the friendly path, and (2) the UNIQUE index
 * {@code ux_cession_source_event} — the belt-and-braces path when a
 * concurrent second delivery races past the read. Recovery-side
 * idempotency uses the pre-existing {@code recovery_cession_uq}
 * UNIQUE (cession_id).
 *
 * <p>Recovery(EXPECTED) is written here rather than in a payment
 * consumer — see the Phase 3 Deviation in
 * {@code thoughts/shared/plans/2026-08-22-reinsurance-module-and-bordereau-reports.md}
 * for rationale.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CessionService {

    private static final String CESSION_ENTITY_TYPE = "Cession";
    private static final String RECOVERY_ENTITY_TYPE = "Recovery";
    private static final String AGENDA_GROUP = "REINSURANCE";

    private final CessionRepository cessionRepository;
    private final RecoveryRepository recoveryRepository;
    private final TreatyRepository treatyRepository;
    private final TenantRuleLoader tenantRuleLoader;
    private final RuleEvaluationService ruleEvaluationService;
    private final AuditPublisher auditPublisher;

    /**
     * Called by the loss cession consumer per adjudicated APPROVED claim.
     * For each ACTIVE treaty covering {@code event.insuranceLine()},
     * fires the REINSURANCE agenda group. Writes one Cession row per
     * {@code CEDE_TO_TREATY} result whose treaty id matches the treaty
     * being tested, plus one EXPECTED Recovery per new Cession.
     *
     * <p>Returns the newly-written cessions — empty when no treaty
     * matched, when no rule fired, or when the cession already existed
     * (idempotent skip).
     */
    @Transactional
    public Flux<Cession> processAdjudicatedClaim(ClaimAdjudicatedEvent event,
                                                 String actorId, String actorEmail) {
        if (event.insuranceLine() == null || event.approvedAmount() == null
                || event.approvedAmount().signum() <= 0) {
            log.debug("Skipping reinsurance dispatch — missing line or non-positive amount ({})",
                    event.claimId());
            return Flux.empty();
        }
        String tenantId = event.tenantId();
        if (tenantId == null || tenantId.isBlank()) {
            log.debug("Skipping reinsurance dispatch — no tenant id on event {}", event.claimId());
            return Flux.empty();
        }
        UUID tenantUuid;
        try {
            tenantUuid = UUID.fromString(tenantId);
        } catch (IllegalArgumentException e) {
            log.warn("Skipping reinsurance dispatch — tenant id not a UUID: {}", tenantId);
            return Flux.empty();
        }

        return treatyRepository.findActiveByInsuranceLine(event.insuranceLine())
                .collectList()
                .flatMapMany(treaties -> {
                    if (treaties.isEmpty()) {
                        log.debug("No ACTIVE treaties for line={} — skipping reinsurance dispatch",
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
     * Fires the REINSURANCE agenda-gated rules once for the claim and
     * returns every {@code CEDE_TO_TREATY} result. The rule content is
     * responsible for filtering to the right treaty via {@code code}
     * (the treaty id passed through {@link com.medfund.rules.compiler.CedeToTreatyEmitter}).
     */
    private Flux<RuleResult> fireCessionRules(String tenantId, ClaimAdjudicatedEvent event) {
        ClaimFact fact = new ClaimFact();
        fact.setClaimId(event.claimId() != null ? event.claimId().toString() : null);
        fact.setMemberId(event.memberId() != null ? event.memberId().toString() : null);
        fact.setProviderId(event.providerId() != null ? event.providerId().toString() : null);
        fact.setAmount(event.approvedAmount());
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
                    log.warn("CEDE_TO_TREATY targeted treaty {} that is not ACTIVE for this line — skipping",
                            treatyId);
                    return Mono.empty();
                });
    }

    private Mono<Cession> writeCession(Treaty treaty, RuleResult result,
                                       ClaimAdjudicatedEvent event,
                                       String actorId, String actorEmail) {
        // hasElement() short-circuits Reactor's switchIfEmpty trap where a
        // flatMap-returned-empty would look identical to a "no row" source
        // and re-run the insert path.
        return cessionRepository.findByTreatyIdAndSourceEventIdAndCessionType(
                        treaty.getId(), event.claimId(), "LOSS")
                .hasElement()
                .flatMap(exists -> {
                    if (Boolean.TRUE.equals(exists)) {
                        log.debug("Cession already exists for treaty={} claim={} — idempotent skip",
                                treaty.getId(), event.claimId());
                        return Mono.empty();
                    }
                    return insertCessionAndRecovery(treaty, result, event, actorId, actorEmail);
                });
    }

    private Mono<Cession> insertCessionAndRecovery(Treaty treaty, RuleResult result,
                                                   ClaimAdjudicatedEvent event,
                                                   String actorId, String actorEmail) {
        UUID layerId = parseLayerId(result.getLayerId());
        Cession c = new Cession();
        c.setTreatyId(treaty.getId());
        c.setTreatyLayerId(layerId);
        c.setCessionType("LOSS");
        c.setSource("AUTOMATIC");
        c.setStatus("ACTIVE");
        c.setSourceEventId(event.claimId());
        c.setSourceEventType("CLAIM_ADJUDICATED");
        c.setBasisAmount(event.approvedAmount());
        c.setCededAmount(result.getAdjustedAmount());
        c.setCurrencyCode(event.currencyCode());
        c.setOccurredAt(event.occurredAt() != null ? event.occurredAt() : OffsetDateTime.now());
        c.setActorId(parseUuid(actorId));
        c.setActorEmail(actorEmail);

        return cessionRepository.save(c)
                .onErrorResume(err -> {
                    // Race: a concurrent second delivery slipped past the read
                    // and won the UNIQUE (treaty_id, source_event_id, cession_type)
                    // insert. Treat as a no-op — the effect is already realised.
                    if (DbErrors.isUniqueViolation(err)) {
                        log.info("Cession UNIQUE race — treaty={} claim={} — idempotent skip",
                                treaty.getId(), event.claimId());
                        return Mono.empty();
                    }
                    return Mono.error(err);
                })
                .flatMap(saved -> publishCessionAudit(saved, treaty, actorId, actorEmail)
                        .then(insertRecovery(saved, actorId, actorEmail))
                        .thenReturn(saved));
    }

    private Mono<Recovery> insertRecovery(Cession cession, String actorId, String actorEmail) {
        Recovery r = new Recovery();
        r.setCessionId(cession.getId());
        r.setStatus("EXPECTED");
        r.setExpectedAmount(cession.getCededAmount());
        r.setCurrencyCode(cession.getCurrencyCode());
        r.setActorId(parseUuid(actorId));
        r.setActorEmail(actorEmail);
        return recoveryRepository.save(r)
                .onErrorResume(err -> {
                    if (DbErrors.isUniqueViolation(err)) {
                        log.info("Recovery UNIQUE race for cession {} — idempotent skip",
                                cession.getId());
                        return Mono.empty();
                    }
                    return Mono.error(err);
                })
                .flatMap(saved -> publishRecoveryAudit(saved, cession, actorId, actorEmail)
                        .thenReturn(saved));
    }

    private Mono<Void> publishCessionAudit(Cession cession, Treaty treaty,
                                           String actorId, String actorEmail) {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            String entityName = "Cession on treaty " + treaty.getTreatyRef()
                    + " — " + cession.getCededAmount().toPlainString()
                    + " " + cession.getCurrencyCode();
            Map<String, Object> newValue = new LinkedHashMap<>();
            newValue.put("treatyId",         cession.getTreatyId().toString());
            newValue.put("treatyLayerId",    cession.getTreatyLayerId() != null
                    ? cession.getTreatyLayerId().toString() : null);
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

    private Mono<Void> publishRecoveryAudit(Recovery recovery, Cession cession,
                                            String actorId, String actorEmail) {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            String entityName = "Recovery on cession " + cession.getId()
                    + " — " + recovery.getExpectedAmount().toPlainString()
                    + " " + recovery.getCurrencyCode() + " EXPECTED";
            Map<String, Object> newValue = new LinkedHashMap<>();
            newValue.put("cessionId",       recovery.getCessionId().toString());
            newValue.put("status",          recovery.getStatus());
            newValue.put("expectedAmount",  recovery.getExpectedAmount().toPlainString());
            newValue.put("currencyCode",    recovery.getCurrencyCode());
            var event = AuditEvent.create(
                    tenantId != null ? tenantId : "unknown",
                    RECOVERY_ENTITY_TYPE,
                    recovery.getId().toString(),
                    entityName,
                    "CREATE",
                    actorId != null ? actorId : "system",
                    actorEmail,
                    null, newValue,
                    new String[]{"expectedAmount", "status"},
                    UUID.randomUUID().toString());
            return auditPublisher.publish(event);
        });
    }

    private UUID parseLayerId(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            log.warn("CEDE_TO_TREATY emitted non-UUID layerId '{}' — storing null", raw);
            return null;
        }
    }

    private UUID parseUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
    }

    /** Ensure BigDecimal arithmetic never inadvertently uses doubles elsewhere. */
    @SuppressWarnings("unused")
    private BigDecimal zero() { return BigDecimal.ZERO; }
}
