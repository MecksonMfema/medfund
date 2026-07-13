package com.medfund.finance.service;

import com.medfund.finance.dto.CtcPaymentDtos.CreateCtcPaymentRequest;
import com.medfund.finance.dto.CtcPaymentFilterParams;
import com.medfund.finance.dto.CtcPaymentRow;
import com.medfund.finance.dto.PageResponse;
import com.medfund.finance.entity.CtcPayment;
import com.medfund.finance.repository.CtcPaymentQueryRepository;
import com.medfund.finance.repository.CtcPaymentRepository;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CtcPaymentService {

    private final CtcPaymentRepository repository;
    private final CtcPaymentQueryRepository queryRepository;
    private final AuditPublisher auditPublisher;

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
            .switchIfEmpty(Mono.error(new IllegalArgumentException("CTC payment not found: " + id)));
    }

    public Mono<CtcPayment> create(CreateCtcPaymentRequest request, String actor, String actorEmail) {
        if (request.groupId() == null && request.memberId() == null) {
            return Mono.error(new IllegalArgumentException("Either groupId or memberId is required"));
        }
        var entity = new CtcPayment();
        entity.setGroupId(request.groupId());
        entity.setMemberId(request.memberId());
        entity.setAmount(request.amount());
        entity.setCurrencyCode(request.currencyCode());
        entity.setContributionId(request.contributionId());
        entity.setCommitted(false);
        return repository.save(entity)
            .flatMap(saved -> publishAudit("CREATE", saved, null, snapshot(saved), actor, actorEmail).thenReturn(saved));
    }

    public Mono<CtcPayment> commit(UUID id, String actor, String actorEmail) {
        return repository.findById(id)
            .switchIfEmpty(Mono.error(new IllegalArgumentException("CTC payment not found: " + id)))
            .flatMap(existing -> {
                if (Boolean.TRUE.equals(existing.getCommitted())) return Mono.just(existing);
                Map<String, Object> before = snapshot(existing);
                existing.setCommitted(true);
                return repository.save(existing)
                    .flatMap(saved -> publishAudit("UPDATE", saved, before, snapshot(saved), actor, actorEmail).thenReturn(saved));
            });
    }

    private Map<String, Object> snapshot(CtcPayment c) {
        Map<String, Object> snap = new HashMap<>();
        snap.put("amount", c.getAmount().toPlainString());
        snap.put("currencyCode", c.getCurrencyCode());
        snap.put("groupId", c.getGroupId() != null ? c.getGroupId().toString() : null);
        snap.put("memberId", c.getMemberId() != null ? c.getMemberId().toString() : null);
        snap.put("committed", c.getCommitted());
        return snap;
    }

    private Mono<Void> publishAudit(String action, CtcPayment c, Map<String, Object> before, Map<String, Object> after, String actor, String actorEmail) {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            String entityName = "CTC " + c.getAmount().toPlainString() + " " + c.getCurrencyCode();
            var event = AuditEvent.create(
                tenantId != null ? tenantId : "unknown",
                "CtcPayment",
                c.getId().toString(),
                entityName,
                action,
                actor != null ? actor : "system",
                actorEmail,
                before,
                after,
                new String[]{},
                UUID.randomUUID().toString()
            );
            return auditPublisher.publish(event);
        });
    }
}
