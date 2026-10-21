package com.medfund.user.service;

import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.tenant.TenantContext;
import com.medfund.user.dto.CreateVariableFeeScheduleRequest;
import com.medfund.user.dto.UpdateVariableFeeScheduleRequest;
import com.medfund.user.entity.VariableFeeSchedule;
import com.medfund.user.exception.UnitLinkedFundNotFoundException;
import com.medfund.user.exception.VariableFeeScheduleNotFoundException;
import com.medfund.user.repository.UnitLinkedFundRepository;
import com.medfund.user.repository.VariableFeeScheduleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Phase 15 §8 (I4) — effective-date-versioned variable fee % per fund.
 * §16 VFA compute picks the effective row via
 * {@link #findEffectiveOn(UUID, LocalDate)}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VariableFeeScheduleService {

    static final String AUDIT_ENTITY_TYPE = "VariableFeeSchedule";

    private final VariableFeeScheduleRepository repository;
    private final UnitLinkedFundRepository fundRepository;
    private final R2dbcEntityTemplate r2dbcTemplate;
    private final AuditPublisher auditPublisher;

    public Flux<VariableFeeSchedule> findByFundId(UUID fundId) {
        return repository.findByFundIdOrderByEffectiveFromDesc(fundId);
    }

    public Mono<VariableFeeSchedule> findEffectiveOn(UUID fundId, LocalDate asOf) {
        return repository.findEffectiveOn(fundId, asOf);
    }

    public Mono<VariableFeeSchedule> findById(UUID id) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new VariableFeeScheduleNotFoundException(id)));
    }

    @Transactional
    public Mono<VariableFeeSchedule> create(UUID fundId, CreateVariableFeeScheduleRequest request,
                                            String actorId, String actorEmail) {
        if (request.effectiveTo() != null
                && !request.effectiveTo().isAfter(request.effectiveFrom())) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "effective_to must be after effective_from"));
        }
        return fundRepository.findById(fundId)
                .switchIfEmpty(Mono.error(new UnitLinkedFundNotFoundException(fundId)))
                .flatMap(fund -> {
                    var row = new VariableFeeSchedule();
                    // Leave id null per bug_r2dbc_pre_populated_id_update_mode.
                    row.setFundId(fundId);
                    row.setEffectiveFrom(request.effectiveFrom());
                    row.setEffectiveTo(request.effectiveTo());
                    row.setFeePercentage(request.feePercentage());
                    row.setActorId(actorId != null ? UUID.fromString(actorId) : null);
                    row.setActorEmail(actorEmail);
                    return r2dbcTemplate.insert(row);
                })
                .onErrorMap(DataIntegrityViolationException.class, ex -> new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "A fee schedule with this effective_from already exists for the fund"))
                .flatMap(saved -> publishAudit(saved, "CREATE", null, actorId, actorEmail)
                        .thenReturn(saved));
    }

    @Transactional
    public Mono<VariableFeeSchedule> update(UUID id, UpdateVariableFeeScheduleRequest request,
                                            String actorId, String actorEmail) {
        return findById(id).flatMap(existing -> {
            if (request.effectiveTo() != null
                    && !request.effectiveTo().isAfter(existing.getEffectiveFrom())) {
                return Mono.<VariableFeeSchedule>error(new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "effective_to must be after effective_from"));
            }
            Map<String, Object> oldValue = snapshot(existing);
            existing.setEffectiveTo(request.effectiveTo());
            existing.setFeePercentage(request.feePercentage());
            if (actorId != null) existing.setActorId(UUID.fromString(actorId));
            existing.setActorEmail(actorEmail);
            return repository.save(existing)
                    .flatMap(saved -> publishAudit(saved, "UPDATE", oldValue, actorId, actorEmail)
                            .thenReturn(saved));
        });
    }

    @Transactional
    public Mono<Void> delete(UUID id, String actorId, String actorEmail) {
        return findById(id).flatMap(existing -> {
            Map<String, Object> oldValue = snapshot(existing);
            return repository.deleteById(id)
                    .then(publishAudit(existing, "DELETE", oldValue, actorId, actorEmail));
        });
    }

    private Map<String, Object> snapshot(VariableFeeSchedule s) {
        Map<String, Object> m = new HashMap<>();
        m.put("fundId", s.getFundId());
        m.put("effectiveFrom", s.getEffectiveFrom());
        m.put("effectiveTo", s.getEffectiveTo());
        m.put("feePercentage", s.getFeePercentage());
        return m;
    }

    private Mono<Void> publishAudit(VariableFeeSchedule s, String action,
                                    Map<String, Object> oldValue,
                                    String actorId, String actorEmail) {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            Map<String, Object> newValue = snapshot(s);
            String[] changed = oldValue == null
                    ? new String[]{"fundId", "effectiveFrom", "effectiveTo", "feePercentage"}
                    : oldValue.keySet().stream()
                            .filter(k -> !Objects.equals(oldValue.get(k), newValue.get(k)))
                            .toArray(String[]::new);
            String friendlyName = s.getFeePercentage() + " from " + s.getEffectiveFrom()
                    + (s.getEffectiveTo() != null ? " to " + s.getEffectiveTo() : "");
            var event = AuditEvent.create(
                    tenantId != null ? tenantId : "unknown",
                    AUDIT_ENTITY_TYPE,
                    s.getId().toString(),
                    friendlyName,
                    action,
                    actorId,
                    actorEmail,
                    oldValue,
                    newValue,
                    changed,
                    UUID.randomUUID().toString()
            );
            return auditPublisher.publish(event);
        });
    }
}
