package com.medfund.finance.service;

import com.medfund.finance.dto.CreateAdjustmentRequest;
import com.medfund.finance.entity.Adjustment;
import com.medfund.finance.exception.AdjustmentNotFoundException;
import com.medfund.finance.repository.AdjustmentRepository;
import com.medfund.finance.util.Actors;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class AdjustmentService {

    private static final Logger log = LoggerFactory.getLogger(AdjustmentService.class);

    private final AdjustmentRepository adjustmentRepository;
    private final AuditPublisher auditPublisher;
    private final FinanceEventPublisher eventPublisher;

    public AdjustmentService(AdjustmentRepository adjustmentRepository,
                             AuditPublisher auditPublisher,
                             FinanceEventPublisher eventPublisher) {
        this.adjustmentRepository = adjustmentRepository;
        this.auditPublisher = auditPublisher;
        this.eventPublisher = eventPublisher;
    }

    public Flux<Adjustment> findByProviderId(UUID providerId) {
        return adjustmentRepository.findByProviderId(providerId);
    }

    public Flux<Adjustment> findByStatus(String status) {
        return adjustmentRepository.findByStatus(status);
    }

    public Mono<Adjustment> findById(UUID id) {
        return adjustmentRepository.findById(id)
            .switchIfEmpty(Mono.error(new AdjustmentNotFoundException(id)));
    }

    @Transactional
    public Mono<Adjustment> create(CreateAdjustmentRequest request, String actorId) {
        return generateAdjustmentNumber()
            .flatMap(adjustmentNumber -> {
                var adjustment = new Adjustment();
                adjustment.setId(UUID.randomUUID());
                adjustment.setAdjustmentNumber(adjustmentNumber);
                adjustment.setProviderId(request.providerId());
                adjustment.setMemberId(request.memberId());
                adjustment.setAdjustmentType(request.adjustmentType());
                adjustment.setAmount(request.amount());
                adjustment.setCurrencyCode(request.currencyCode());
                adjustment.setReason(request.reason());
                adjustment.setStatus("pending");
                adjustment.setCreatedAt(Instant.now());
                adjustment.setUpdatedAt(Instant.now());
                adjustment.setCreatedBy(Actors.parseId(actorId));

                return adjustmentRepository.save(adjustment);
            })
            .flatMap(saved -> Mono.deferContextual(ctx -> {
                String tenantId = TenantContext.get(ctx);
                return publishAudit(tenantId, "Adjustment", saved.getId().toString(), "CREATE", actorId,
                        null,
                        Map.of("adjustmentNumber", saved.getAdjustmentNumber(), "status", saved.getStatus(),
                               "amount", saved.getAmount().toString(), "type", saved.getAdjustmentType()))
                    .thenReturn(saved);
            }));
    }

    @Transactional
    public Mono<Adjustment> approve(UUID id, String actorId) {
        return adjustmentRepository.findById(id)
            .switchIfEmpty(Mono.error(new AdjustmentNotFoundException(id)))
            .flatMap(adjustment -> {
                String previousStatus = adjustment.getStatus();
                adjustment.setStatus("approved");
                adjustment.setApprovedBy(Actors.parseId(actorId));
                adjustment.setApprovedAt(Instant.now());
                adjustment.setUpdatedAt(Instant.now());

                return adjustmentRepository.save(adjustment)
                    .flatMap(saved -> Mono.deferContextual(ctx -> {
                        String tenantId = TenantContext.get(ctx);
                        return publishAudit(tenantId, "Adjustment", saved.getId().toString(), "UPDATE", actorId,
                                Map.of("status", previousStatus),
                                Map.of("status", saved.getStatus()))
                            .thenReturn(saved);
                    }));
            });
    }

    @Transactional
    public Mono<Adjustment> apply(UUID id, String actorId) {
        return adjustmentRepository.findById(id)
            .switchIfEmpty(Mono.error(new AdjustmentNotFoundException(id)))
            .flatMap(adjustment -> {
                String previousStatus = adjustment.getStatus();
                adjustment.setStatus("applied");
                adjustment.setUpdatedAt(Instant.now());

                return adjustmentRepository.save(adjustment)
                    .flatMap(saved -> Mono.deferContextual(ctx -> {
                        String tenantId = TenantContext.get(ctx);
                        return publishAudit(tenantId, "Adjustment", saved.getId().toString(), "UPDATE", actorId,
                                Map.of("status", previousStatus),
                                Map.of("status", saved.getStatus()))
                            .then(eventPublisher.publishAdjustmentApplied(
                                saved.getId().toString(),
                                saved.getAdjustmentType(),
                                saved.getAmount().toString()))
                            .thenReturn(saved);
                    }));
            });
    }

    @Transactional
    public Mono<Adjustment> cancel(UUID id, String actorId) {
        return adjustmentRepository.findById(id)
            .switchIfEmpty(Mono.error(new AdjustmentNotFoundException(id)))
            .flatMap(adjustment -> {
                String previousStatus = adjustment.getStatus();
                if ("applied".equals(previousStatus)) {
                    return Mono.error(new IllegalStateException("Cannot cancel an applied adjustment"));
                }
                if ("cancelled".equals(previousStatus)) return Mono.just(adjustment);
                adjustment.setStatus("cancelled");
                adjustment.setUpdatedAt(Instant.now());

                return adjustmentRepository.save(adjustment)
                    .flatMap(saved -> Mono.deferContextual(ctx -> {
                        String tenantId = TenantContext.get(ctx);
                        return publishAudit(tenantId, "Adjustment", saved.getId().toString(), "UPDATE", actorId,
                                Map.of("status", previousStatus),
                                Map.of("status", saved.getStatus()))
                            .thenReturn(saved);
                    }));
            });
    }

    // ---- Private helpers ----

    private Mono<String> generateAdjustmentNumber() {
        String number = "ADJ-" + ThreadLocalRandom.current().nextInt(100000, 999999);
        return adjustmentRepository.existsByAdjustmentNumber(number)
            .flatMap(exists -> exists ? generateAdjustmentNumber() : Mono.just(number));
    }

    private Mono<Void> publishAudit(String tenantId, String entityType, String entityId,
                                     String action, String actorId,
                                     Map<String, Object> oldValue, Map<String, Object> newValue) {
        var event = AuditEvent.create(
            tenantId != null ? tenantId : "unknown",
            entityType,
            entityId,
            action,
            actorId,
            null,
            oldValue,
            newValue,
            new String[]{"status"},
            UUID.randomUUID().toString()
        );
        return auditPublisher.publish(event);
    }
}
