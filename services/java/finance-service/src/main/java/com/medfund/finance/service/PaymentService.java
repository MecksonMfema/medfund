package com.medfund.finance.service;

import com.medfund.finance.dto.CreatePaymentRequest;
import com.medfund.finance.dto.PageResponse;
import com.medfund.finance.dto.PaymentFilterParams;
import com.medfund.finance.dto.PaymentRow;
import com.medfund.finance.entity.Payment;
import com.medfund.finance.exception.PaymentNotFoundException;
import com.medfund.finance.repository.PaymentQueryRepository;
import com.medfund.finance.repository.PaymentRepository;
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
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository paymentRepository;
    private final PaymentQueryRepository queryRepository;
    private final AuditPublisher auditPublisher;
    private final FinanceEventPublisher eventPublisher;

    public PaymentService(PaymentRepository paymentRepository,
                          PaymentQueryRepository queryRepository,
                          AuditPublisher auditPublisher,
                          FinanceEventPublisher eventPublisher) {
        this.paymentRepository = paymentRepository;
        this.queryRepository = queryRepository;
        this.auditPublisher = auditPublisher;
        this.eventPublisher = eventPublisher;
    }

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
                    }));
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
