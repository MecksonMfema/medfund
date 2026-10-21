package com.medfund.finance.producer.service;

import com.medfund.finance.dto.PageResponse;
import com.medfund.finance.producer.dto.CreateRateCardRequest;
import com.medfund.finance.producer.dto.RateCardResponse;
import com.medfund.finance.producer.dto.UpdateRateCardRequest;
import com.medfund.finance.producer.entity.CommissionRateCard;
import com.medfund.finance.producer.repository.CommissionRateCardRepository;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * CRUD for commission rate cards. Deactivation is a soft close: {@code
 * active = false} and {@code effective_to} snapped to last-day-of-month per
 * {@code feedback_effective_date_snap}. Overlapping active cards with the
 * same {@code (insurance_line, producer_tier)} are permitted — resolution is
 * "most-recent-effective" via {@link CommissionRateCardRepository#findApplicable}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommissionRateCardService {

    private static final String ENTITY_TYPE = "CommissionRateCard";

    private final CommissionRateCardRepository repository;
    private final AuditPublisher auditPublisher;

    public Mono<PageResponse<RateCardResponse>> list(int page, int size, Boolean active) {
        int offset = page * size;
        var content = (active != null
                ? repository.findPageByActive(active, offset, size)
                : repository.findPage(offset, size))
                .map(RateCardResponse::from)
                .collectList();
        var total = (active != null ? repository.countByActive(active) : repository.countAll());
        return content.zipWith(total, (rows, t) -> PageResponse.of(rows, t, page, size));
    }

    public Mono<RateCardResponse> get(UUID id) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Rate card not found: " + id)))
                .map(RateCardResponse::from);
    }

    public Mono<RateCardResponse> findApplicable(String insuranceLine, String tier, LocalDate asOf) {
        return repository.findApplicable(insuranceLine, tier, asOf).map(RateCardResponse::from);
    }

    @Transactional
    public Mono<RateCardResponse> create(CreateRateCardRequest req, String actorId, String actorEmail) {
        validatePeriod(req.effectiveFrom(), req.effectiveTo());
        return Mono.defer(() -> {
            CommissionRateCard c = new CommissionRateCard();
            c.setName(req.name());
            c.setInsuranceLine(req.insuranceLine());
            c.setProducerTier(req.producerTier());
            c.setBaseRatePct(req.baseRatePct());
            c.setClawbackWindowDays(req.clawbackWindowDays());
            c.setEffectiveFrom(snapStart(req.effectiveFrom()));
            c.setEffectiveTo(req.effectiveTo() == null ? null : snapEnd(req.effectiveTo()));
            c.setActive(true);
            OffsetDateTime now = OffsetDateTime.now();
            c.setCreatedAt(now);
            c.setUpdatedAt(now);
            c.setActorId(parseUuid(actorId));
            c.setActorEmail(actorEmail);
            return repository.save(c);
        }).flatMap(saved -> publishAudit("CREATE", saved, null, snapshot(saved), actorId, actorEmail)
                .thenReturn(RateCardResponse.from(saved)));
    }

    @Transactional
    public Mono<RateCardResponse> update(UUID id, UpdateRateCardRequest req,
                                         String actorId, String actorEmail) {
        validatePeriod(req.effectiveFrom(), req.effectiveTo());
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Rate card not found: " + id)))
                .flatMap(existing -> {
                    Map<String, Object> before = snapshot(existing);
                    existing.setName(req.name());
                    existing.setInsuranceLine(req.insuranceLine());
                    existing.setProducerTier(req.producerTier());
                    existing.setBaseRatePct(req.baseRatePct());
                    existing.setClawbackWindowDays(req.clawbackWindowDays());
                    existing.setEffectiveFrom(snapStart(req.effectiveFrom()));
                    existing.setEffectiveTo(req.effectiveTo() == null ? null : snapEnd(req.effectiveTo()));
                    existing.setActive(req.active());
                    existing.setUpdatedAt(OffsetDateTime.now());
                    existing.setActorId(parseUuid(actorId));
                    existing.setActorEmail(actorEmail);
                    return repository.save(existing)
                            .flatMap(saved -> publishAudit("UPDATE", saved, before, snapshot(saved),
                                    actorId, actorEmail)
                                    .thenReturn(RateCardResponse.from(saved)));
                });
    }

    /**
     * Soft-close: flip {@code active=false} and snap {@code effective_to} to
     * the last day of the current month per {@code feedback_effective_date_snap}.
     */
    @Transactional
    public Mono<RateCardResponse> deactivate(UUID id, String actorId, String actorEmail) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Rate card not found: " + id)))
                .flatMap(existing -> {
                    if (Boolean.FALSE.equals(existing.getActive())) {
                        return Mono.just(RateCardResponse.from(existing));
                    }
                    Map<String, Object> before = snapshot(existing);
                    existing.setActive(false);
                    existing.setEffectiveTo(LocalDate.now().with(TemporalAdjusters.lastDayOfMonth()));
                    existing.setUpdatedAt(OffsetDateTime.now());
                    existing.setActorId(parseUuid(actorId));
                    existing.setActorEmail(actorEmail);
                    return repository.save(existing)
                            .flatMap(saved -> publishAudit("DEACTIVATE", saved, before, snapshot(saved),
                                    actorId, actorEmail)
                                    .thenReturn(RateCardResponse.from(saved)));
                });
    }

    private void validatePeriod(LocalDate from, LocalDate to) {
        if (from == null) {
            throw new IllegalArgumentException("effectiveFrom is required");
        }
        if (to != null && to.isBefore(from)) {
            throw new IllegalArgumentException("effectiveTo must be on or after effectiveFrom");
        }
    }

    private LocalDate snapStart(LocalDate d) {
        return d.withDayOfMonth(1);
    }

    private LocalDate snapEnd(LocalDate d) {
        return d.with(TemporalAdjusters.lastDayOfMonth());
    }

    private Map<String, Object> snapshot(CommissionRateCard c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name",               c.getName());
        m.put("insuranceLine",      c.getInsuranceLine());
        m.put("producerTier",       c.getProducerTier());
        m.put("baseRatePct",        c.getBaseRatePct());
        m.put("clawbackWindowDays", c.getClawbackWindowDays());
        m.put("effectiveFrom",      c.getEffectiveFrom());
        m.put("effectiveTo",        c.getEffectiveTo());
        m.put("active",             c.getActive());
        return m;
    }

    private String[] diff(Map<String, Object> before, Map<String, Object> after) {
        if (before == null || after == null) return new String[0];
        return before.keySet().stream()
                .filter(k -> !Objects.equals(before.get(k), after.get(k)))
                .toArray(String[]::new);
    }

    private Mono<Void> publishAudit(String action, CommissionRateCard entity,
                                    Map<String, Object> before, Map<String, Object> after,
                                    String actorId, String actorEmail) {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            var event = AuditEvent.create(
                    tenantId != null ? tenantId : "unknown",
                    ENTITY_TYPE,
                    entity.getId().toString(),
                    entity.getName(),
                    action,
                    actorId != null ? actorId : "system",
                    actorEmail,
                    before, after,
                    diff(before, after),
                    UUID.randomUUID().toString());
            return auditPublisher.publish(event);
        });
    }

    private UUID parseUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
    }
}
