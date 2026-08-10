package com.medfund.finance.service;

import com.medfund.finance.dto.CreatePaymentRequest;
import com.medfund.finance.dto.PageResponse;
import com.medfund.finance.dto.PaymentFilterParams;
import com.medfund.finance.dto.PaymentRow;
import com.medfund.finance.entity.MemberPayable;
import com.medfund.finance.entity.MemberPayableApplication;
import com.medfund.finance.entity.Payment;
import com.medfund.finance.entity.PaymentRun;
import com.medfund.finance.entity.PaymentRunItem;
import com.medfund.finance.exception.PaymentNotFoundException;
import com.medfund.finance.repository.MemberPayableApplicationRepository;
import com.medfund.finance.repository.MemberPayableBalanceRepository;
import com.medfund.finance.repository.MemberPayableRepository;
import com.medfund.finance.repository.PaymentQueryRepository;
import com.medfund.finance.repository.PaymentRepository;
import com.medfund.finance.repository.PaymentRunItemRepository;
import com.medfund.finance.repository.PaymentRunRepository;
import com.medfund.finance.util.Actors;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final PaymentQueryRepository queryRepository;
    private final AuditPublisher auditPublisher;
    private final FinanceEventPublisher eventPublisher;

    // Wired in Phase 3 — MEMBER settlement path. Provider payments still
    // fall through the pre-existing branch and touch none of these.
    private final MemberBalanceService memberBalanceService;
    private final MemberPayableRepository memberPayableRepository;
    private final MemberPayableApplicationRepository applicationRepository;
    private final MemberPayableBalanceRepository memberPayableBalanceRepository;
    private final PaymentRunItemRepository paymentRunItemRepository;
    private final PaymentRunRepository paymentRunRepository;

    public Flux<Payment> findAll() {
        return paymentRepository.findAllOrderByCreatedAtDesc();
    }

    /**
     * Server-side paginated payments list. Provider name joined so the
     * operational payments table renders inline.
     */
    public Mono<PageResponse<PaymentRow>> searchPaged(PaymentFilterParams params) {
        int page = Math.max(params.page(), 0);
        int size = Math.min(Math.max(params.size(), 1), 200);
        int offset = page * size;
        return queryRepository.search(params, size, offset)
                .collectList()
                .zipWith(queryRepository.count(params))
                .map(tuple -> PageResponse.of(tuple.getT1(), tuple.getT2(), page, size));
    }

    public Mono<Payment> findById(UUID id) {
        return paymentRepository.findById(id)
            .switchIfEmpty(Mono.error(new PaymentNotFoundException(id)));
    }

    public Flux<Payment> findByProviderId(UUID providerId) {
        return paymentRepository.findByProviderId(providerId);
    }

    public Flux<Payment> findByMemberId(UUID memberId) {
        return paymentRepository.findByMemberId(memberId);
    }

    public Flux<Payment> findByStatus(String status) {
        return paymentRepository.findByStatus(status);
    }

    @Transactional
    public Mono<Payment> create(CreatePaymentRequest request, String actorId, String actorEmail) {
        return generatePaymentNumber()
            .flatMap(paymentNumber -> {
                var payment = new Payment();
                payment.setPaymentNumber(paymentNumber);
                payment.setProviderId(request.providerId());
                payment.setAmount(request.amount());
                payment.setCurrencyCode(request.currencyCode());
                payment.setPaymentType(request.paymentType());
                payment.setStatus("pending");
                payment.setPaymentMethod(request.paymentMethod());
                payment.setReference(request.reference());
                payment.setCreatedAt(Instant.now());
                payment.setUpdatedAt(Instant.now());
                payment.setCreatedBy(Actors.parseId(actorId));
                payment.setUpdatedBy(Actors.parseId(actorId));

                return paymentRepository.save(payment);
            })
            .flatMap(saved -> Mono.deferContextual(ctx -> {
                String tenantId = TenantContext.get(ctx);
                return publishAudit(tenantId, "Payment", saved.getId().toString(), saved.getPaymentNumber(),
                        "CREATE", actorId, actorEmail,
                        null,
                        Map.of("paymentNumber", saved.getPaymentNumber(), "status", saved.getStatus(),
                               "amount", saved.getAmount().toString()))
                    .then(eventPublisher.publishPaymentCreated(
                        saved.getId().toString(),
                        saved.getProviderId().toString(),
                        saved.getAmount().toString()))
                    .thenReturn(saved);
            }));
    }

    @Transactional
    public Mono<Payment> markPaid(UUID id, String actorId, String actorEmail) {
        return paymentRepository.findById(id)
            .switchIfEmpty(Mono.error(new PaymentNotFoundException(id)))
            .flatMap(payment -> {
                String previousStatus = payment.getStatus();
                payment.setStatus("paid");
                payment.setPaidAt(Instant.now());
                payment.setUpdatedAt(Instant.now());
                payment.setUpdatedBy(Actors.parseId(actorId));

                return paymentRepository.save(payment)
                    .flatMap(saved -> Mono.deferContextual(ctx -> {
                        String tenantId = TenantContext.get(ctx);
                        return publishAudit(tenantId, "Payment", saved.getId().toString(), saved.getPaymentNumber(),
                                "UPDATE", actorId, actorEmail,
                                Map.of("status", previousStatus),
                                Map.of("status", saved.getStatus()))
                            .then(eventPublisher.publishPaymentCommitted(
                                saved.getId().toString(),
                                saved.getProviderId() != null ? saved.getProviderId().toString() : "",
                                saved.getAmount() != null ? saved.getAmount().toPlainString() : "0",
                                saved.getCurrencyCode()))
                            .thenReturn(saved);
                    }))
                    .flatMap(saved -> "MEMBER".equalsIgnoreCase(saved.getPayeeType())
                            ? settleMemberPayment(saved, actorId, actorEmail).thenReturn(saved)
                            : Mono.just(saved));
            });
    }

    @Transactional
    public Mono<Payment> cancel(UUID id, String actorId, String actorEmail) {
        return paymentRepository.findById(id)
            .switchIfEmpty(Mono.error(new PaymentNotFoundException(id)))
            .flatMap(payment -> {
                String previousStatus = payment.getStatus();
                if ("paid".equals(previousStatus)) {
                    return Mono.error(new IllegalStateException("Cannot cancel a paid payment — post a reversing adjustment"));
                }
                if ("cancelled".equals(previousStatus)) return Mono.just(payment);
                payment.setStatus("cancelled");
                payment.setUpdatedAt(Instant.now());
                payment.setUpdatedBy(Actors.parseId(actorId));

                return paymentRepository.save(payment)
                    .flatMap(saved -> Mono.deferContextual(ctx -> {
                        String tenantId = TenantContext.get(ctx);
                        return publishAudit(tenantId, "Payment", saved.getId().toString(), saved.getPaymentNumber(),
                                "UPDATE", actorId, actorEmail,
                                Map.of("status", previousStatus),
                                Map.of("status", saved.getStatus()))
                            .thenReturn(saved);
                    }));
            });
    }

    /**
     * Revoke a pending Payment from its parent PaymentRun. Deletes the
     * child PaymentRunItem + the Payment, recomputes {@code paymentCount}
     * and {@code total_amount} on the parent run, and emits a DELETE
     * audit event carrying the full row snapshot (so the audit trail can
     * reconstruct what disappeared).
     *
     * <p>Guarded to prevent revoking a Payment that has moved past
     * {@code pending}, or whose parent run has already been executed or
     * cancelled — both are cases where deleting silently would corrupt
     * ledger state or bank-file exports.
     *
     * <p>The payee's outstanding balance is untouched — they'll be
     * enumerated again by the next run generation. This is the whole
     * point of "revoke rather than cancel": the operator wanted this
     * payee out of THIS batch, not paid off.
     */
    @Transactional
    public Mono<Void> revoke(UUID paymentId, String actorId, String actorEmail) {
        return paymentRepository.findById(paymentId)
            .switchIfEmpty(Mono.error(new PaymentNotFoundException(paymentId)))
            .flatMap(payment -> {
                if (!"pending".equalsIgnoreCase(payment.getStatus())) {
                    return Mono.error(new IllegalStateException(
                            "Only pending payments can be revoked; status=" + payment.getStatus()));
                }
                return paymentRunItemRepository.findByPaymentId(paymentId)
                    .switchIfEmpty(Mono.error(new IllegalStateException(
                            "Payment " + paymentId + " has no parent run — cannot revoke")))
                    .flatMap(item -> paymentRunRepository.findById(item.getPaymentRunId())
                        .switchIfEmpty(Mono.error(new IllegalStateException(
                                "Payment run " + item.getPaymentRunId() + " not found")))
                        .flatMap(run -> {
                            if (!List.of("draft", "approved").contains(run.getStatus())) {
                                return Mono.error(new IllegalStateException(
                                        "Cannot revoke payment while run is " + run.getStatus()));
                            }
                            Map<String, Object> oldValue = snapshotForAudit(payment);
                            return paymentRunItemRepository.delete(item)
                                    .then(paymentRepository.delete(payment))
                                    .then(recomputeRunTotals(run.getId()))
                                    .then(publishRevokeAudit(payment, oldValue, actorId, actorEmail));
                        }));
            });
    }

    // ---- MEMBER settlement helpers (Phase 3) ----

    /**
     * When a MEMBER-payee Payment moves to {@code paid}, run the
     * finance-side settlement fan-out: bump {@code member_balances.total_paid}
     * and FIFO-allocate the payment amount across the member's open (and
     * partially-applied) payables via {@code member_payable_applications}
     * with {@code source_type='PAYMENT'}. When a payable is fully consumed
     * flip its status to {@code applied}.
     */
    private Mono<Void> settleMemberPayment(Payment payment, String actorId, String actorEmail) {
        return memberBalanceService.updateBalance(
                    payment.getMemberId(), payment.getCurrencyCode(),
                    null, null, payment.getAmount(),
                    actorId, actorEmail)
                .then(allocateFifoToPayables(payment, actorId));
    }

    /**
     * Walk this member's open + partially-applied payables in FIFO order
     * (oldest {@code recorded_at} first) and consume the payment amount
     * by writing {@code member_payable_applications} rows until the
     * amount runs out. Filter by currency so a USD payment doesn't
     * consume an EUR payable. Uses {@code concatMap} so writes serialize
     * — parallel writes would fight over {@code remainingOn}.
     */
    private Mono<Void> allocateFifoToPayables(Payment payment, String actorId) {
        BigDecimal[] remainingHolder = { payment.getAmount() };
        return memberPayableRepository
                .findByMemberIdAndStatusInOrderByRecordedAtAsc(
                        payment.getMemberId(), "open", "applied")
                .filter(p -> payment.getCurrencyCode() != null
                        && payment.getCurrencyCode().equalsIgnoreCase(p.getCurrencyCode()))
                .concatMap(payable -> {
                    if (remainingHolder[0].signum() <= 0) return Mono.empty();
                    return memberPayableBalanceRepository.remainingOn(payable.getId())
                            .flatMap(remainingOnPayable -> {
                                if (remainingOnPayable == null || remainingOnPayable.signum() <= 0) {
                                    return Mono.empty();
                                }
                                BigDecimal slice = remainingHolder[0].min(remainingOnPayable);
                                remainingHolder[0] = remainingHolder[0].subtract(slice);
                                return writeApplication(payable, payment, slice, actorId)
                                        .then(remainingOnPayable.compareTo(slice) == 0
                                                ? maybeFlipPayableStatus(payable)
                                                : Mono.empty());
                            });
                })
                .then();
    }

    private Mono<MemberPayableApplication> writeApplication(MemberPayable payable, Payment payment,
                                                             BigDecimal slice, String actorId) {
        MemberPayableApplication app = new MemberPayableApplication();
        app.setMemberPayableId(payable.getId());
        app.setSourceType("PAYMENT");
        app.setSourceId(payment.getId());
        app.setAmountApplied(slice);
        app.setCurrencyCode(payment.getCurrencyCode());
        app.setAppliedAt(Instant.now());
        app.setAppliedBy(Actors.parseId(actorId));
        return applicationRepository.save(app);
    }

    private Mono<Void> maybeFlipPayableStatus(MemberPayable payable) {
        if ("applied".equalsIgnoreCase(payable.getStatus())) return Mono.empty();
        payable.setStatus("applied");
        return memberPayableRepository.save(payable).then();
    }

    // ---- Revoke helpers (Phase 3) ----

    private Map<String, Object> snapshotForAudit(Payment payment) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("paymentNumber", payment.getPaymentNumber());
        snap.put("status",        payment.getStatus());
        snap.put("amount",        payment.getAmount() != null ? payment.getAmount().toString() : null);
        snap.put("currencyCode",  payment.getCurrencyCode());
        snap.put("payeeType",     payment.getPayeeType());
        snap.put("providerId",    payment.getProviderId() != null ? payment.getProviderId().toString() : null);
        snap.put("memberId",      payment.getMemberId() != null ? payment.getMemberId().toString() : null);
        return snap;
    }

    private Mono<Void> recomputeRunTotals(UUID runId) {
        return paymentRunItemRepository.findByPaymentRunId(runId).collectList()
                .flatMap(items -> paymentRunRepository.findById(runId).flatMap(run -> {
                    BigDecimal total = items.stream()
                            .map(PaymentRunItem::getAmount)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    run.setPaymentCount(items.size());
                    run.setTotalAmount(total);
                    run.setUpdatedAt(Instant.now());
                    return paymentRunRepository.save(run);
                }))
                .then();
    }

    private Mono<Void> publishRevokeAudit(Payment payment, Map<String, Object> oldValue,
                                           String actorId, String actorEmail) {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            var event = AuditEvent.create(
                    tenantId != null ? tenantId : "unknown",
                    "Payment",
                    payment.getId().toString(),
                    payment.getPaymentNumber(),
                    "DELETE",
                    actorId,
                    actorEmail,
                    oldValue,
                    null,
                    oldValue.keySet().toArray(new String[0]),
                    UUID.randomUUID().toString()
            );
            return auditPublisher.publish(event);
        });
    }

    // ---- Private helpers ----

    private Mono<String> generatePaymentNumber() {
        String number = "PAY-" + ThreadLocalRandom.current().nextInt(100000, 999999);
        return paymentRepository.existsByPaymentNumber(number)
            .flatMap(exists -> exists ? generatePaymentNumber() : Mono.just(number));
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
}
