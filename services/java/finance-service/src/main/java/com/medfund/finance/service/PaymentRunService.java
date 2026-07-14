package com.medfund.finance.service;

import com.medfund.finance.dto.CreatePaymentRunRequest;
import com.medfund.finance.entity.PaymentRun;
import com.medfund.finance.entity.PaymentRunItem;
import com.medfund.finance.exception.PaymentNotFoundException;
import com.medfund.finance.repository.PaymentRepository;
import com.medfund.finance.repository.PaymentRunItemRepository;
import com.medfund.finance.repository.PaymentRunRepository;
import com.medfund.finance.repository.ProviderBalanceRepository;
import com.medfund.finance.util.Actors;

import java.math.BigDecimal;
import com.medfund.shared.audit.AuditActor;
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
public class PaymentRunService {

    private static final Logger log = LoggerFactory.getLogger(PaymentRunService.class);

    private final PaymentRunRepository paymentRunRepository;
    private final com.medfund.finance.repository.PaymentRunQueryRepository queryRepository;
    private final PaymentRunItemRepository paymentRunItemRepository;
    private final PaymentRepository paymentRepository;
    private final ProviderBalanceRepository providerBalanceRepository;
    private final AuditPublisher auditPublisher;
    private final FinanceEventPublisher eventPublisher;
    private final PaymentRunDecisionService decisionService;

    public PaymentRunService(PaymentRunRepository paymentRunRepository,
                             com.medfund.finance.repository.PaymentRunQueryRepository queryRepository,
                             PaymentRunItemRepository paymentRunItemRepository,
                             PaymentRepository paymentRepository,
                             ProviderBalanceRepository providerBalanceRepository,
                             AuditPublisher auditPublisher,
                             FinanceEventPublisher eventPublisher,
                             PaymentRunDecisionService decisionService) {
        this.paymentRunRepository = paymentRunRepository;
        this.queryRepository = queryRepository;
        this.paymentRunItemRepository = paymentRunItemRepository;
        this.paymentRepository = paymentRepository;
        this.providerBalanceRepository = providerBalanceRepository;
        this.auditPublisher = auditPublisher;
        this.eventPublisher = eventPublisher;
        this.decisionService = decisionService;
    }

    /**
     * Server-side paginated payment-runs list. Self-contained header
     * rows — no joins.
     */
    public reactor.core.publisher.Mono<com.medfund.finance.dto.PageResponse<com.medfund.finance.entity.PaymentRun>>
    searchPaged(com.medfund.finance.dto.PaymentRunFilterParams params) {
        int page = Math.max(params.page(), 0);
        int size = Math.min(Math.max(params.size(), 1), 200);
        int offset = page * size;
        return queryRepository.search(params, size, offset)
                .collectList()
                .zipWith(queryRepository.count(params))
                .map(tuple -> com.medfund.finance.dto.PageResponse.of(tuple.getT1(), tuple.getT2(), page, size));
    }

    public Flux<PaymentRun> findAll() {
        return paymentRunRepository.findAllOrderByCreatedAtDesc();
    }

    public Mono<PaymentRun> findById(UUID id) {
        return paymentRunRepository.findById(id)
            .switchIfEmpty(Mono.error(new PaymentNotFoundException(id)));
    }

    public Flux<PaymentRunItem> findItems(UUID runId) {
        return paymentRunItemRepository.findByPaymentRunId(runId);
    }

    @Transactional
    public Mono<PaymentRun> create(CreatePaymentRunRequest request, String actorId, String actorEmail) {
        return generateRunNumber()
            .flatMap(runNumber -> {
                var run = new PaymentRun();
                run.setRunNumber(runNumber);
                run.setStatus("draft");
                run.setCurrencyCode(request.currencyCode());
                run.setDescription(request.description());
                run.setPaymentCount(0);
                run.setCreatedAt(Instant.now());
                run.setUpdatedAt(Instant.now());
                run.setCreatedBy(Actors.parseId(actorId));

                return paymentRunRepository.save(run);
            })
            .flatMap(saved -> Mono.deferContextual(ctx -> {
                String tenantId = TenantContext.get(ctx);
                return publishAudit(tenantId, "PaymentRun", saved.getId().toString(), saved.getRunNumber(),
                        "CREATE", actorId, actorEmail,
                        null,
                        Map.of("runNumber", saved.getRunNumber(), "status", saved.getStatus()))
                    .then(eventPublisher.publishPaymentRunCreated(
                        saved.getId().toString(),
                        saved.getRunNumber(),
                        saved.getCurrencyCode(),
                        saved.getTotalAmount() != null ? saved.getTotalAmount().toPlainString() : "0",
                        saved.getPaymentCount() != null ? saved.getPaymentCount() : 0))
                    .thenReturn(saved);
            }));
    }

    @Transactional
    public Mono<PaymentRun> execute(UUID runId, String actorId, String actorEmail) {
        return paymentRunRepository.findById(runId)
            .switchIfEmpty(Mono.error(new PaymentNotFoundException(runId)))
            .flatMap(run -> {
                String previousStatus = run.getStatus();
                if (!"draft".equals(previousStatus) && !"approved".equals(previousStatus)) {
                    return Mono.error(new IllegalStateException(
                        "Payment run " + run.getRunNumber() + " must be in draft or approved status, current: " + previousStatus));
                }

                run.setStatus("executing");
                run.setUpdatedAt(Instant.now());

                return paymentRunRepository.save(run)
                    .flatMap(inProgress -> applyTenantRulesToItems(inProgress.getId())
                        .thenReturn(inProgress))
                    .flatMap(this::recomputeRunTotal)
                    .flatMap(inProgress -> {
                        // Transition to executed (final terminal state for the happy path).
                        inProgress.setStatus("executed");
                        inProgress.setExecutedAt(Instant.now());
                        inProgress.setExecutedBy(Actors.parseId(actorId));
                        inProgress.setUpdatedAt(Instant.now());

                        return paymentRunRepository.save(inProgress);
                    })
                    .flatMap(completed -> Mono.deferContextual(ctx -> {
                        String tenantId = TenantContext.get(ctx);
                        return publishAudit(tenantId, "PaymentRun", completed.getId().toString(), completed.getRunNumber(),
                                "UPDATE", actorId, actorEmail,
                                Map.of("status", previousStatus),
                                Map.of("status", completed.getStatus()))
                            .then(eventPublisher.publishPaymentRunExecuted(
                                completed.getId().toString(),
                                completed.getRunNumber(),
                                completed.getPaymentCount() != null ? completed.getPaymentCount() : 0))
                            .thenReturn(completed);
                    }));
            });
    }

    @Transactional
    public Mono<PaymentRun> approve(UUID runId, String actorId, String actorEmail) {
        return paymentRunRepository.findById(runId)
            .switchIfEmpty(Mono.error(new PaymentNotFoundException(runId)))
            .flatMap(run -> {
                String previousStatus = run.getStatus();
                if (!"draft".equals(previousStatus)) {
                    return Mono.error(new IllegalStateException(
                        "Payment run " + run.getRunNumber() + " must be in draft to approve, current: " + previousStatus));
                }
                run.setStatus("approved");
                run.setUpdatedAt(Instant.now());
                return paymentRunRepository.save(run)
                    .flatMap(saved -> Mono.deferContextual(ctx -> {
                        String tenantId = TenantContext.get(ctx);
                        return publishAudit(tenantId, "PaymentRun", saved.getId().toString(), saved.getRunNumber(),
                                "UPDATE", actorId, actorEmail,
                                Map.of("status", previousStatus),
                                Map.of("status", saved.getStatus()))
                            .then(eventPublisher.publishPaymentRunApproved(
                                saved.getId().toString(),
                                saved.getRunNumber(),
                                actorId != null ? actorId : "system"))
                            .thenReturn(saved);
                    }));
            });
    }

    @Transactional
    public Mono<PaymentRun> cancel(UUID runId, String actorId, String actorEmail) {
        return paymentRunRepository.findById(runId)
            .switchIfEmpty(Mono.error(new PaymentNotFoundException(runId)))
            .flatMap(run -> {
                String previousStatus = run.getStatus();
                if ("executed".equals(previousStatus)) {
                    return Mono.error(new IllegalStateException(
                        "Payment run " + run.getRunNumber() + " is already executed and cannot be cancelled"));
                }
                if ("cancelled".equals(previousStatus)) return Mono.just(run);
                run.setStatus("cancelled");
                run.setUpdatedAt(Instant.now());
                return paymentRunRepository.save(run)
                    .flatMap(saved -> Mono.deferContextual(ctx -> {
                        String tenantId = TenantContext.get(ctx);
                        return publishAudit(tenantId, "PaymentRun", saved.getId().toString(), saved.getRunNumber(),
                                "UPDATE", actorId, actorEmail,
                                Map.of("status", previousStatus),
                                Map.of("status", saved.getStatus()))
                            .then(eventPublisher.publishPaymentRunCancelled(
                                saved.getId().toString(),
                                saved.getRunNumber(),
                                "manual"))
                            .thenReturn(saved);
                    }));
            });
    }

    /**
     * Run PROVIDER_PAYMENT + RECONCILIATION rules over every item in the run.
     * The decision service mutates each item in place: status flips to
     * {@code scheduled} when a SCHEDULE_PAYMENT_RUN rule fires, and
     * {@code amount} is reduced by the withhold portion when WITHHOLD_PAYMENT
     * fires. Items where the rules choose not to schedule (no scheduling rule
     * matched + no withhold) are left at their current status — payment
     * downstream code is expected to skip non-{@code scheduled} items.
     *
     * <p>Tenants without finance rules see no behaviour change — every item's
     * status / amount stays as it was.
     */
    private Mono<Void> applyTenantRulesToItems(UUID runId) {
        return paymentRunItemRepository.findByPaymentRunId(runId)
            .flatMap(item -> decisionService.decide(item)
                .then(paymentRunItemRepository.save(item)))
            .then();
    }

    /**
     * Recalculate the run's {@code totalAmount} after withholds may have
     * shrunk individual item amounts. Cheap — runs are typically tens of
     * items, not thousands.
     */
    private Mono<PaymentRun> recomputeRunTotal(PaymentRun run) {
        return paymentRunItemRepository.findByPaymentRunId(run.getId())
            .map(PaymentRunItem::getAmount)
            .filter(amt -> amt != null)
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .flatMap(total -> {
                run.setTotalAmount(total);
                run.setUpdatedAt(Instant.now());
                return paymentRunRepository.save(run);
            });
    }

    // ---- Private helpers ----

    private Mono<String> generateRunNumber() {
        String number = "RUN-" + ThreadLocalRandom.current().nextInt(100000, 999999);
        return paymentRunRepository.existsByRunNumber(number)
            .flatMap(exists -> exists ? generateRunNumber() : Mono.just(number));
    }

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

    public Mono<Void> autoExecuteDraftRuns() {
        java.time.Instant cutoff = java.time.Instant.now().minus(java.time.Duration.ofHours(24));
        return paymentRunRepository.findByStatus("draft")
            .filter(run -> run.getCreatedAt() != null && run.getCreatedAt().isBefore(cutoff))
            .flatMap(run -> execute(run.getId(), AuditActor.SYSTEM_ID, AuditActor.SYSTEM_EMAIL))
            .then();
    }
}
