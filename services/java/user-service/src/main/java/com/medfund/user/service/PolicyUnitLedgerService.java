package com.medfund.user.service;

import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.tenant.TenantContext;
import com.medfund.user.dto.CreatePolicyUnitLedgerRequest;
import com.medfund.user.entity.PolicyUnitLedger;
import com.medfund.user.exception.UnitLinkedFundNotFoundException;
import com.medfund.user.repository.PolicyUnitLedgerRepository;
import com.medfund.user.repository.UnitLinkedFundRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 15 §8 (I4) — append-only per-policy unit ledger. Called by the
 * policy issuance flow on initial VFA policy issuance and on subsequent fund
 * switches; §16 VFA reads the running-balance snapshot at reporting-period
 * boundaries.
 *
 * <p>Every append emits an {@link AuditEvent} per Rule 8 with a friendly
 * entity name ({@code "policy:<policy-id> <type> <units> units"}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PolicyUnitLedgerService {

    static final String AUDIT_ENTITY_TYPE = "PolicyUnitLedger";

    private final PolicyUnitLedgerRepository repository;
    private final UnitLinkedFundRepository fundRepository;
    private final R2dbcEntityTemplate r2dbcTemplate;
    private final AuditPublisher auditPublisher;

    public Flux<PolicyUnitLedger> findByPolicyId(UUID policyId) {
        return repository.findByPolicyId(policyId);
    }

    public Flux<PolicyUnitLedger> findByFundId(UUID fundId) {
        return repository.findByFundId(fundId);
    }

    @Transactional
    public Mono<PolicyUnitLedger> append(UUID fundId, CreatePolicyUnitLedgerRequest request,
                                         String actorId, String actorEmail) {
        return fundRepository.findById(fundId)
                .switchIfEmpty(Mono.error(new UnitLinkedFundNotFoundException(fundId)))
                .flatMap(fund -> {
                    var row = new PolicyUnitLedger();
                    // Leave id null per bug_r2dbc_pre_populated_id_update_mode.
                    row.setPolicyId(request.policyId());
                    row.setFundId(fundId);
                    row.setTransactionDate(request.transactionDate());
                    row.setTransactionType(request.transactionType());
                    row.setUnits(request.units());
                    row.setPrice(request.price());
                    row.setActorId(actorId != null ? UUID.fromString(actorId) : null);
                    row.setActorEmail(actorEmail);
                    return r2dbcTemplate.insert(row);
                })
                .flatMap(saved -> publishAudit(saved, actorId, actorEmail).thenReturn(saved));
    }

    private Mono<Void> publishAudit(PolicyUnitLedger row, String actorId, String actorEmail) {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            Map<String, Object> newValue = new HashMap<>();
            newValue.put("policyId", row.getPolicyId());
            newValue.put("fundId", row.getFundId());
            newValue.put("transactionDate", row.getTransactionDate());
            newValue.put("transactionType", row.getTransactionType());
            newValue.put("units", row.getUnits());
            newValue.put("price", row.getPrice());
            String friendlyName = "policy:" + row.getPolicyId() + " "
                    + row.getTransactionType() + " " + row.getUnits() + " units";
            var event = AuditEvent.create(
                    tenantId != null ? tenantId : "unknown",
                    AUDIT_ENTITY_TYPE,
                    row.getId().toString(),
                    friendlyName,
                    "CREATE",
                    actorId,
                    actorEmail,
                    null,
                    newValue,
                    new String[]{"policyId", "fundId", "transactionType", "units", "price"},
                    UUID.randomUUID().toString()
            );
            return auditPublisher.publish(event);
        });
    }
}
