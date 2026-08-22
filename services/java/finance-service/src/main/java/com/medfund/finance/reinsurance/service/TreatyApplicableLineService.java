package com.medfund.finance.reinsurance.service;

import com.medfund.finance.reinsurance.dto.CreateTreatyApplicableLineRequest;
import com.medfund.finance.reinsurance.dto.TreatyApplicableLineResponse;
import com.medfund.finance.reinsurance.entity.TreatyApplicableLine;
import com.medfund.finance.reinsurance.repository.TreatyApplicableLineRepository;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The treaty's applicable-lines set. Add + remove; no update. Composite
 * key means the create is idempotent — a duplicate insert is bounced by
 * the PK; controllers return the existing row on 409.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TreatyApplicableLineService {

    private static final String ENTITY_TYPE = "TreatyApplicableLine";

    private final TreatyApplicableLineRepository repository;
    private final TreatyService treatyService;
    private final AuditPublisher auditPublisher;

    public Flux<TreatyApplicableLineResponse> list(UUID treatyId) {
        return repository.findByTreatyId(treatyId).map(TreatyApplicableLineResponse::from);
    }

    @Transactional
    public Mono<TreatyApplicableLineResponse> add(UUID treatyId, CreateTreatyApplicableLineRequest req,
                                                  String actorId, String actorEmail) {
        return treatyService.requireDraft(treatyId).flatMap(treaty -> repository.insert(treatyId, req.insuranceLine())
                .flatMap(saved -> publishAudit("CREATE", saved, treaty.getTreatyRef(),
                                null, snapshot(saved), actorId, actorEmail)
                        .thenReturn(TreatyApplicableLineResponse.from(saved))));
    }

    @Transactional
    public Mono<Void> remove(UUID treatyId, String insuranceLine, String actorId, String actorEmail) {
        return treatyService.requireDraft(treatyId).flatMap(treaty -> {
            TreatyApplicableLine snapshot = new TreatyApplicableLine();
            snapshot.setTreatyId(treatyId);
            snapshot.setInsuranceLine(insuranceLine);
            Map<String, Object> before = snapshot(snapshot);
            return repository.delete(treatyId, insuranceLine)
                    .flatMap(rows -> rows == 0
                            ? Mono.error(new IllegalArgumentException(
                                    "Line " + insuranceLine + " not on treaty " + treatyId))
                            : publishAudit("DELETE", snapshot, treaty.getTreatyRef(),
                                    before, null, actorId, actorEmail));
        });
    }

    private Map<String, Object> snapshot(TreatyApplicableLine l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("treatyId",      l.getTreatyId());
        m.put("insuranceLine", l.getInsuranceLine());
        return m;
    }

    private Mono<Void> publishAudit(String action, TreatyApplicableLine entity, String treatyRef,
                                    Map<String, Object> before, Map<String, Object> after,
                                    String actorId, String actorEmail) {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            var event = AuditEvent.create(
                    tenantId != null ? tenantId : "unknown",
                    ENTITY_TYPE,
                    entity.getTreatyId() + ":" + entity.getInsuranceLine(),
                    entity.getInsuranceLine() + " on treaty " + treatyRef,
                    action,
                    actorId != null ? actorId : "system",
                    actorEmail,
                    before,
                    after,
                    new String[0],
                    UUID.randomUUID().toString());
            return auditPublisher.publish(event);
        });
    }
}
