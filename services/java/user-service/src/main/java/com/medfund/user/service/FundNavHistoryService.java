package com.medfund.user.service;

import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.tenant.TenantContext;
import com.medfund.user.dto.CreateFundNavHistoryRequest;
import com.medfund.user.entity.FundNavHistory;
import com.medfund.user.exception.FundNavHistoryNotFoundException;
import com.medfund.user.exception.UnitLinkedFundNotFoundException;
import com.medfund.user.repository.FundNavHistoryRepository;
import com.medfund.user.repository.UnitLinkedFundRepository;
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
import java.util.UUID;

/**
 * Phase 15 §8 (I4) — daily NAV series per fund. Read-only after insert per
 * V162 constraint; corrections go via a new valuation_date row. §16 VFA
 * compute reads {@link #findLatestFor(UUID, LocalDate)} at reporting-period
 * boundaries.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FundNavHistoryService {

    static final String AUDIT_ENTITY_TYPE = "FundNavHistory";

    private final FundNavHistoryRepository repository;
    private final UnitLinkedFundRepository fundRepository;
    private final R2dbcEntityTemplate r2dbcTemplate;
    private final AuditPublisher auditPublisher;

    public Flux<FundNavHistory> findByFundId(UUID fundId) {
        return repository.findByFundIdOrderByValuationDateDesc(fundId);
    }

    public Mono<FundNavHistory> findLatestFor(UUID fundId, LocalDate asOf) {
        return repository.findLatestFor(fundId, asOf);
    }

    public Mono<FundNavHistory> findById(UUID id) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new FundNavHistoryNotFoundException(id)));
    }

    @Transactional
    public Mono<FundNavHistory> create(UUID fundId, CreateFundNavHistoryRequest request,
                                       String actorId, String actorEmail) {
        if (request.valuationDate().isAfter(LocalDate.now())) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "valuation_date must not be in the future"));
        }
        return fundRepository.findById(fundId)
                .switchIfEmpty(Mono.error(new UnitLinkedFundNotFoundException(fundId)))
                .flatMap(fund -> {
                    var row = new FundNavHistory();
                    // Leave id null per bug_r2dbc_pre_populated_id_update_mode.
                    row.setFundId(fundId);
                    row.setValuationDate(request.valuationDate());
                    row.setNavPerUnit(request.navPerUnit());
                    row.setSource("ADMIN");
                    row.setActorId(actorId != null ? UUID.fromString(actorId) : null);
                    row.setActorEmail(actorEmail);
                    return r2dbcTemplate.insert(row);
                })
                .onErrorMap(DataIntegrityViolationException.class, ex -> new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "A NAV row for this fund + valuation_date already exists"))
                .flatMap(saved -> publishAudit(saved, "CREATE", actorId, actorEmail)
                        .thenReturn(saved));
    }

    private Mono<Void> publishAudit(FundNavHistory row, String action,
                                    String actorId, String actorEmail) {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            Map<String, Object> newValue = new HashMap<>();
            newValue.put("fundId", row.getFundId());
            newValue.put("valuationDate", row.getValuationDate());
            newValue.put("navPerUnit", row.getNavPerUnit());
            newValue.put("source", row.getSource());
            // Friendly entityName per feedback_audit_entity_name — never UUID.
            String friendlyName = "NAV " + row.getNavPerUnit() + " @ " + row.getValuationDate();
            var event = AuditEvent.create(
                    tenantId != null ? tenantId : "unknown",
                    AUDIT_ENTITY_TYPE,
                    row.getId().toString(),
                    friendlyName,
                    action,
                    actorId,
                    actorEmail,
                    null,
                    newValue,
                    new String[]{"fundId", "valuationDate", "navPerUnit", "source"},
                    UUID.randomUUID().toString()
            );
            return auditPublisher.publish(event);
        });
    }
}
