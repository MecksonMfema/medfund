package com.medfund.finance.service;

import com.medfund.finance.client.FxConverter;
import com.medfund.finance.dto.CreatePaymentRunRequest;
import com.medfund.finance.entity.AdvancePayment;
import com.medfund.finance.entity.AdvancePaymentApplication;
import com.medfund.finance.entity.PaymentRun;
import com.medfund.finance.entity.PaymentRunItem;
import com.medfund.finance.exception.PaymentNotFoundException;
import com.medfund.finance.repository.AdvancePaymentApplicationRepository;
import com.medfund.finance.repository.AdvancePaymentBalanceRepository;
import com.medfund.finance.repository.AdvancePaymentRepository;
import com.medfund.finance.repository.OutstandingAdvanceBalance;
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
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.LocalDate;
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
    private final AdvancePaymentRepository advancePaymentRepository;
    private final AdvancePaymentBalanceRepository advanceBalanceRepository;
    private final AdvancePaymentApplicationRepository advanceApplicationRepository;
    private final FxConverter fxConverter;
    private final AuditPublisher auditPublisher;
    private final FinanceEventPublisher eventPublisher;
    private final PaymentRunDecisionService decisionService;
    private final DatabaseClient databaseClient;

    public PaymentRunService(PaymentRunRepository paymentRunRepository,
                             com.medfund.finance.repository.PaymentRunQueryRepository queryRepository,
                             PaymentRunItemRepository paymentRunItemRepository,
                             PaymentRepository paymentRepository,
                             ProviderBalanceRepository providerBalanceRepository,
                             AdvancePaymentRepository advancePaymentRepository,
                             AdvancePaymentBalanceRepository advanceBalanceRepository,
                             AdvancePaymentApplicationRepository advanceApplicationRepository,
                             FxConverter fxConverter,
                             AuditPublisher auditPublisher,
                             FinanceEventPublisher eventPublisher,
                             PaymentRunDecisionService decisionService,
                             DatabaseClient databaseClient) {
        this.paymentRunRepository = paymentRunRepository;
        this.queryRepository = queryRepository;
        this.paymentRunItemRepository = paymentRunItemRepository;
        this.paymentRepository = paymentRepository;
        this.providerBalanceRepository = providerBalanceRepository;
        this.advancePaymentRepository = advancePaymentRepository;
        this.advanceBalanceRepository = advanceBalanceRepository;
        this.advanceApplicationRepository = advanceApplicationRepository;
        this.fxConverter = fxConverter;
        this.auditPublisher = auditPublisher;
        this.eventPublisher = eventPublisher;
        this.decisionService = decisionService;
        this.databaseClient = databaseClient;
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
                    .flatMap(this::snapshotCarryOut)
                    .flatMap(this::snapshotSettlementDate)
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
     * <p>Advance offset seam: {@code advancePaid} is aggregated per
     * (provider, currency) from {@code advance_payments} and passed into
     * the rule engine. If a tenant has an active PROVIDER_PAYMENT rule that
     * withholds when {@code advancePaid >= amountDue} (the shipped starter
     * template), the run item's amount will drop by the withheld portion.
     * Every drop is then recorded FIFO into {@code advance_payment_applications}
     * so "how much of provider X's outstanding advance has been consumed"
     * is a single-query answer, and each consumed advance flips to
     * {@code applied} once its balance is fully drawn down.
     *
     * <p>Tenants without finance rules see no behaviour change — advancePaid
     * is still fed into the engine, but with no matching rule the item's
     * amount and status stay as they were.
     */
    private Mono<Void> applyTenantRulesToItems(UUID runId) {
        return paymentRunItemRepository.findByPaymentRunId(runId)
            .flatMap(item -> resolveAdvancePaid(item)
                .flatMap(advancePaid -> {
                    BigDecimal preRuleAmount = item.getAmount() == null ? BigDecimal.ZERO : item.getAmount();
                    return decisionService.decide(item, advancePaid)
                        .then(paymentRunItemRepository.save(item))
                        .flatMap(saved -> recordApplicationsIfConsumed(saved, preRuleAmount)
                            .thenReturn(saved));
                }))
            .then();
    }

    /**
     * Aggregate outstanding advance balance for the item's provider, converted
     * to the item's currency. Returns ZERO if the item has no provider (e.g.
     * member-payee runs, which today don't exist but may later) or no open
     * balance in any currency.
     */
    private Mono<BigDecimal> resolveAdvancePaid(PaymentRunItem item) {
        UUID payeeId = item.getProviderId();
        if (payeeId == null || item.getCurrencyCode() == null) {
            return Mono.just(BigDecimal.ZERO);
        }
        String targetCurrency = item.getCurrencyCode();
        return Mono.deferContextual(ctx -> {
            UUID tenantId = parseTenant(TenantContext.get(ctx));
            return advanceBalanceRepository.findOutstandingByProvider(payeeId)
                .flatMap(bal -> targetCurrency.equalsIgnoreCase(bal.currencyCode())
                    ? Mono.just(bal.outstanding())
                    : fxConverter.convert(bal.outstanding(), bal.currencyCode(),
                                          targetCurrency, LocalDate.now(), tenantId)
                        .onErrorResume(err -> {
                            log.warn("[advance-offset] FX {}->{} failed for run item {} — skipping "
                                    + "that balance line: {}",
                                    bal.currencyCode(), targetCurrency, item.getId(), err.getMessage());
                            return Mono.empty();
                        }))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        });
    }

    /**
     * If the rule engine reduced the item's amount, that reduction is what
     * the advance offset "paid for". Record it FIFO against the provider's
     * open advances (oldest first) until we've fully accounted for the drop.
     * Skips silently when: no drop, no provider on item, or no open advance
     * in the item's currency.
     */
    private Mono<Void> recordApplicationsIfConsumed(PaymentRunItem item, BigDecimal preRuleAmount) {
        if (item.getProviderId() == null || item.getCurrencyCode() == null) return Mono.empty();
        BigDecimal postRuleAmount = item.getAmount() == null ? BigDecimal.ZERO : item.getAmount();
        BigDecimal consumed = preRuleAmount.subtract(postRuleAmount);
        if (consumed.signum() <= 0) return Mono.empty();

        return drawDownAdvancesFifo(item, consumed);
    }

    private Mono<Void> drawDownAdvancesFifo(PaymentRunItem item, BigDecimal remainingToApply) {
        if (remainingToApply.signum() <= 0) return Mono.empty();
        return advancePaymentRepository.findOldestOpenForProvider(item.getProviderId(), item.getCurrencyCode())
            .flatMap(advance -> advanceBalanceRepository.remainingOn(advance.getId())
                .flatMap(remainingOnAdvance -> {
                    BigDecimal applyThisRow = remainingToApply.min(remainingOnAdvance);
                    if (applyThisRow.signum() <= 0) return Mono.empty();
                    var app = new AdvancePaymentApplication();
                    app.setAdvancePaymentId(advance.getId());
                    app.setPaymentId(item.getPaymentId());
                    app.setPaymentRunId(item.getPaymentRunId());
                    app.setPaymentRunItemId(item.getId());
                    app.setAmountApplied(applyThisRow);
                    app.setCurrencyCode(item.getCurrencyCode());
                    app.setAppliedAt(Instant.now());
                    app.setAppliedBy(null); // system-initiated via rule engine
                    return advanceApplicationRepository.save(app)
                        .flatMap(saved -> eventPublisher.publishAdvanceApplied(saved).thenReturn(saved))
                        .flatMap(saved -> maybeMarkAdvanceApplied(advance).thenReturn(applyThisRow));
                }))
            .flatMap(applied -> {
                BigDecimal next = remainingToApply.subtract(applied);
                return next.signum() > 0 ? drawDownAdvancesFifo(item, next) : Mono.<Void>empty();
            })
            .then();
    }

    /**
     * If the advance has now been fully drawn down (remaining == 0), flip its
     * status to {@code applied}. Uses the balance repository to authoritatively
     * check remaining, not just the incremental delta above.
     */
    private Mono<Void> maybeMarkAdvanceApplied(AdvancePayment advance) {
        return advanceBalanceRepository.remainingOn(advance.getId())
            .flatMap(remaining -> {
                if (remaining.signum() <= 0 && !"applied".equals(advance.getStatus())) {
                    advance.setStatus("applied");
                    return advancePaymentRepository.save(advance).then();
                }
                return Mono.empty();
            });
    }

    private UUID parseTenant(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return UUID.fromString(raw); } catch (IllegalArgumentException e) { return null; }
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

    /**
     * V067 — snapshot the sum of amounts for items that are NOT settled
     * (i.e. anything other than status='paid'). Captured at execute()
     * time as the run's {@code carried_out_amount}, which the next run's
     * generation flow reads to compute {@code carried_in_amount}.
     */
    private Mono<PaymentRun> snapshotCarryOut(PaymentRun run) {
        return paymentRunItemRepository.findByPaymentRunId(run.getId())
            .filter(item -> item.getAmount() != null && !"paid".equalsIgnoreCase(item.getStatus()))
            .map(PaymentRunItem::getAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .flatMap(carriedOut -> {
                run.setCarriedOutAmount(carriedOut);
                return paymentRunRepository.save(run);
            });
    }

    /**
     * V067 — capture {@code settlement_date} as MAX(payment.paid_at) once
     * every item in the run has transitioned to paid; leave the column
     * null until then. Uses a raw SQL projection because
     * PaymentRunItem doesn't carry payment.paid_at inline.
     *
     * <p>Empty runs (no items yet — the common case in dev / before the
     * item-population flow lands) short-circuit without hitting the DB.
     */
    private Mono<PaymentRun> snapshotSettlementDate(PaymentRun run) {
        return paymentRunItemRepository.findByPaymentRunId(run.getId())
            .count()
            .flatMap(count -> {
                if (count == 0) return Mono.just(run);
                String sql = "SELECT "
                        + "  BOOL_AND(pri.status = 'paid')          AS all_settled, "
                        + "  MAX(p.paid_at)                          AS last_paid_at "
                        + "FROM payment_run_items pri "
                        + "LEFT JOIN payments p ON p.id = pri.payment_id "
                        + "WHERE pri.payment_run_id = :runId";
                return databaseClient.sql(sql)
                    .bind("runId", run.getId())
                    .fetch().one()
                    .flatMap(row -> {
                        Boolean allSettled = (Boolean) row.get("all_settled");
                        Instant lastPaidAt = (Instant) row.get("last_paid_at");
                        if (Boolean.TRUE.equals(allSettled) && lastPaidAt != null) {
                            run.setSettlementDate(lastPaidAt);
                            return paymentRunRepository.save(run);
                        }
                        return Mono.just(run);
                    })
                    .defaultIfEmpty(run);
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
