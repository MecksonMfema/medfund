package com.medfund.finance.reinsurance.job;

import com.medfund.finance.reinsurance.entity.Cession;
import com.medfund.finance.reinsurance.entity.Treaty;
import com.medfund.finance.reinsurance.repository.CessionRepository;
import com.medfund.finance.reinsurance.repository.TreatyRepository;
import com.medfund.finance.util.DbErrors;
import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.scheduler.JobExecutor;
import com.medfund.shared.scheduler.JobType;
import com.medfund.shared.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * {@link JobExecutor} that writes a flat PREMIUM {@link Cession} at
 * inception for every ACTIVE non-proportional treaty (XoL / StopLoss)
 * that carries a positive {@code expected_annual_premium}. Idempotent
 * via the {@code ux_cession_source_event} UNIQUE on
 * (treaty_id, source_event_id=treaty.id, cession_type=PREMIUM) — a
 * re-run writes zero duplicate rows.
 *
 * <p>Runs per-tenant on the tenant's configured cron via
 * {@code scheduled_job_configs}, same shape as {@code PaymentRunExecutor}.
 * The dispatcher wraps the call in tenant context, so this executor
 * doesn't need to touch {@link TenantContext} directly — all repo calls
 * naturally hit the caller's schema.
 *
 * <p>Proportional treaties (Quota / Surplus Share) take their premium
 * per contribution paid via {@code ReinsurancePremiumCessionConsumer}
 * and are intentionally not touched here.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReinsuranceTreatyPremiumExecutor implements JobExecutor {

    private static final String CESSION_ENTITY_TYPE = "Cession";
    private static final String SOURCE_EVENT_TYPE = "TREATY_INCEPTION";

    private final TreatyRepository treatyRepository;
    private final CessionRepository cessionRepository;
    private final AuditPublisher auditPublisher;

    @Override
    public JobType getJobType() {
        return JobType.REINSURANCE_TREATY_PREMIUM;
    }

    @Override
    public Mono<Void> execute(String tenantId, String settings) {
        log.info("Reinsurance treaty premium sweep starting for tenant={}", tenantId);
        return treatyRepository.findActiveNonProportionalWithExpectedPremium()
                .flatMap(this::writePremiumIfMissing)
                .doOnNext(c -> log.info(
                        "Wrote inception premium cession {} for treaty {}",
                        c.getId(), c.getTreatyId()))
                .then()
                .doOnSuccess(v -> log.info(
                        "Reinsurance treaty premium sweep complete for tenant={}", tenantId));
    }

    private Mono<Cession> writePremiumIfMissing(Treaty treaty) {
        String[] systemActor = AuditActor.systemActor();
        return cessionRepository.findByTreatyIdAndSourceEventIdAndCessionType(
                        treaty.getId(), treaty.getId(), "PREMIUM")
                .hasElement()
                .flatMap(exists -> {
                    if (Boolean.TRUE.equals(exists)) {
                        log.debug("Inception premium already ceded for treaty {} — skipping",
                                treaty.getId());
                        return Mono.empty();
                    }
                    return insertInceptionPremium(treaty, systemActor[0], systemActor[1]);
                });
    }

    private Mono<Cession> insertInceptionPremium(Treaty treaty, String actorId, String actorEmail) {
        String currency = treaty.getDeclaredCurrency();
        Cession c = new Cession();
        c.setTreatyId(treaty.getId());
        c.setTreatyLayerId(null);
        c.setCessionType("PREMIUM");
        c.setSource("AUTOMATIC");
        c.setStatus("ACTIVE");
        c.setSourceEventId(treaty.getId());
        c.setSourceEventType(SOURCE_EVENT_TYPE);
        // Basis and ceded amount are equal for a flat inception premium:
        // the whole expected_annual_premium is what we owe the reinsurer.
        c.setBasisAmount(treaty.getExpectedAnnualPremium());
        c.setCededAmount(treaty.getExpectedAnnualPremium());
        c.setCurrencyCode(currency);
        c.setOccurredAt(treaty.getActivatedAt() != null
                ? treaty.getActivatedAt() : OffsetDateTime.now());
        c.setActorId(parseUuid(actorId));
        c.setActorEmail(actorEmail);

        return cessionRepository.save(c)
                .onErrorResume(err -> {
                    if (DbErrors.isUniqueViolation(err)) {
                        log.info("Inception premium UNIQUE race for treaty {} — idempotent skip",
                                treaty.getId());
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
            String entityName = "Inception premium cession on treaty " + treaty.getTreatyRef()
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
