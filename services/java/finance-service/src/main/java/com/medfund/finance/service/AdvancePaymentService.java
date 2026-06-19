package com.medfund.finance.service;

import com.medfund.finance.dto.AdvancePaymentDtos.CreateAdvancePaymentRequest;
import com.medfund.finance.entity.AdvancePayment;
import com.medfund.finance.repository.AdvancePaymentRepository;
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
public class AdvancePaymentService {

    private final AdvancePaymentRepository repository;
    private final AuditPublisher auditPublisher;

    public Flux<AdvancePayment> findAll() {
        return repository.findAllOrdered();
    }

    public Flux<AdvancePayment> findByProvider(UUID providerId) {
        return repository.findByProviderId(providerId);
    }

    public Flux<AdvancePayment> findByMember(UUID memberId) {
        return repository.findByMemberId(memberId);
    }

    public Mono<AdvancePayment> findById(UUID id) {
        return repository.findById(id)
            .switchIfEmpty(Mono.error(new IllegalArgumentException("Advance payment not found: " + id)));
    }

    public Mono<AdvancePayment> create(CreateAdvancePaymentRequest request, String actor, String actorEmail) {
        if (request.providerId() == null && request.memberId() == null) {
            return Mono.error(new IllegalArgumentException("Either providerId or memberId is required"));
        }
        var entity = new AdvancePayment();
        entity.setId(UUID.randomUUID());
        entity.setProviderId(request.providerId());
        entity.setMemberId(request.memberId());
        entity.setAmount(request.amount());
        entity.setCurrencyCode(request.currencyCode());
        entity.setPaymentMethod(request.paymentMethod());
        entity.setReference(request.reference());
        entity.setComment(request.comment());
        return repository.save(entity)
            .flatMap(saved -> publishAudit(saved, actor, actorEmail).thenReturn(saved));
    }

    private Mono<Void> publishAudit(AdvancePayment a, String actor, String actorEmail) {
        Map<String, Object> after = new HashMap<>();
        after.put("amount", a.getAmount().toPlainString());
        after.put("currencyCode", a.getCurrencyCode());
        after.put("providerId", a.getProviderId() != null ? a.getProviderId().toString() : null);
        after.put("memberId", a.getMemberId() != null ? a.getMemberId().toString() : null);
        after.put("reference", a.getReference());
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            var event = AuditEvent.create(
                tenantId != null ? tenantId : "unknown",
                "AdvancePayment",
                a.getId().toString(),
                "CREATE",
                actor != null ? actor : "system",
                actorEmail,
                null,
                after,
                new String[]{},
                UUID.randomUUID().toString()
            );
            return auditPublisher.publish(event);
        });
    }
}
