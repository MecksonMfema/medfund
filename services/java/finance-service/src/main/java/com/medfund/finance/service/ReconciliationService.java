package com.medfund.finance.service;

import com.medfund.finance.dto.CreateReconciliationRequest;
import com.medfund.finance.entity.BankReconciliation;
import com.medfund.finance.repository.BankReconciliationRepository;
import com.medfund.finance.repository.PaymentRepository;
import com.medfund.finance.util.Actors;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.tenant.TenantContext;

import java.time.LocalTime;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final BankReconciliationRepository bankReconciliationRepository;
    private final com.medfund.finance.repository.BankReconciliationQueryRepository queryRepository;
    private final PaymentRepository paymentRepository;
    private final AuditPublisher auditPublisher;

    public ReconciliationService(BankReconciliationRepository bankReconciliationRepository,
                                 com.medfund.finance.repository.BankReconciliationQueryRepository queryRepository,
                                 PaymentRepository paymentRepository,
                                 AuditPublisher auditPublisher) {
        this.bankReconciliationRepository = bankReconciliationRepository;
        this.queryRepository = queryRepository;
        this.paymentRepository = paymentRepository;
        this.auditPublisher = auditPublisher;
    }

    /** Server-side paginated reconciliations list. */
    public reactor.core.publisher.Mono<com.medfund.finance.dto.PageResponse<com.medfund.finance.entity.BankReconciliation>>
    searchPaged(com.medfund.finance.dto.BankReconciliationFilterParams params) {
        int page = Math.max(params.page(), 0);
        int size = Math.min(Math.max(params.size(), 1), 200);
        int offset = page * size;
        return queryRepository.search(params, size, offset)
                .collectList()
                .zipWith(queryRepository.count(params))
                .map(t -> com.medfund.finance.dto.PageResponse.of(t.getT1(), t.getT2(), page, size));
    }

    public Flux<BankReconciliation> findAll() {
        return bankReconciliationRepository.findAllOrderByCreatedAtDesc();
    }

    public Flux<BankReconciliation> findByStatus(String status) {
        return bankReconciliationRepository.findByStatus(status);
    }

    @Transactional
    public Mono<BankReconciliation> create(CreateReconciliationRequest request, String actorId, String actorEmail) {
        // Resolve system amount: explicit override wins; otherwise sum the
        // paid payments in the same currency on/before the statement date.
        Mono<BigDecimal> systemAmountMono = request.systemAmount() != null
            ? Mono.just(request.systemAmount())
            : paymentRepository.sumPaidUpTo(
                request.currencyCode(),
                request.statementDate().atTime(LocalTime.MAX).toInstant(ZoneOffset.UTC))
                .defaultIfEmpty(BigDecimal.ZERO);

        return systemAmountMono.flatMap(systemAmount -> {
            var recon = new BankReconciliation();
            recon.setReferenceNumber(request.referenceNumber());
            recon.setStatementAmount(request.statementAmount());
            recon.setSystemAmount(systemAmount);
            recon.setCurrencyCode(request.currencyCode());
            recon.setStatementDate(request.statementDate());
            recon.setNotes(request.notes());
            recon.setCreatedAt(Instant.now());
            recon.setCreatedBy(Actors.parseId(actorId));

            BigDecimal difference = request.statementAmount().subtract(systemAmount);
            recon.setDifference(difference);
            recon.setStatus(difference.compareTo(BigDecimal.ZERO) == 0 ? "matched" : "unmatched");

            return bankReconciliationRepository.save(recon)
                .flatMap(saved -> Mono.deferContextual(ctx -> {
                    String tenantId = TenantContext.get(ctx);
                    return publishAudit(tenantId, "BankReconciliation", saved.getId().toString(), saved.getReferenceNumber(),
                            "CREATE", actorId, actorEmail,
                            null,
                            Map.of("referenceNumber", saved.getReferenceNumber(), "status", saved.getStatus(),
                                   "difference", saved.getDifference().toString()))
                        .thenReturn(saved);
                }));
        });
    }

    @Transactional
    public Mono<BankReconciliation> markMatched(UUID id, String actorId, String actorEmail) {
        return bankReconciliationRepository.findById(id)
            .switchIfEmpty(Mono.error(new RuntimeException("Bank reconciliation not found: " + id)))
            .flatMap(recon -> {
                String previousStatus = recon.getStatus();
                recon.setStatus("matched");
                recon.setReconciledAt(Instant.now());
                recon.setReconciledBy(Actors.parseId(actorId));

                return bankReconciliationRepository.save(recon)
                    .flatMap(saved -> Mono.deferContextual(ctx -> {
                        String tenantId = TenantContext.get(ctx);
                        return publishAudit(tenantId, "BankReconciliation", saved.getId().toString(), saved.getReferenceNumber(),
                                "UPDATE", actorId, actorEmail,
                                Map.of("status", previousStatus),
                                Map.of("status", saved.getStatus()))
                            .thenReturn(saved);
                    }));
            });
    }

    @Transactional
    public Mono<BankReconciliation> markInvestigating(UUID id, String actorId, String actorEmail) {
        return transitionStatus(id, "investigating", false, actorId, actorEmail);
    }

    @Transactional
    public Mono<BankReconciliation> markResolved(UUID id, String actorId, String actorEmail) {
        return transitionStatus(id, "resolved", true, actorId, actorEmail);
    }

    private Mono<BankReconciliation> transitionStatus(UUID id, String newStatus, boolean stamp, String actorId, String actorEmail) {
        return bankReconciliationRepository.findById(id)
            .switchIfEmpty(Mono.error(new RuntimeException("Bank reconciliation not found: " + id)))
            .flatMap(recon -> {
                String previousStatus = recon.getStatus();
                if (previousStatus.equals(newStatus)) return Mono.just(recon);
                recon.setStatus(newStatus);
                if (stamp) {
                    recon.setReconciledAt(Instant.now());
                    recon.setReconciledBy(Actors.parseId(actorId));
                }
                return bankReconciliationRepository.save(recon)
                    .flatMap(saved -> Mono.deferContextual(ctx -> {
                        String tenantId = TenantContext.get(ctx);
                        return publishAudit(tenantId, "BankReconciliation", saved.getId().toString(), saved.getReferenceNumber(),
                                "UPDATE", actorId, actorEmail,
                                Map.of("status", previousStatus),
                                Map.of("status", saved.getStatus()))
                            .thenReturn(saved);
                    }));
            });
    }

    // ---- Private helpers ----

    private Mono<Void> publishAudit(String tenantId, String entityType, String entityId, String entityName,
                                     String action, String actorId, String actorEmail,
                                     Map<String, Object> oldValue, Map<String, Object> newValue) {
        var event = AuditEvent.create(
            tenantId != null ? tenantId : "unknown",
            entityType,
            entityId,
            entityName,
            action,
            actorId,
            actorEmail,
            oldValue,
            newValue,
            new String[]{"status"},
            UUID.randomUUID().toString()
        );
        return auditPublisher.publish(event);
    }
}
