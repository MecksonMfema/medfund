package com.medfund.finance.service;

import com.medfund.finance.dto.CreatePaymentRequest;
import com.medfund.finance.entity.Payment;
import com.medfund.finance.exception.PaymentNotFoundException;
import com.medfund.finance.repository.PaymentRepository;
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
    private final AuditPublisher auditPublisher;
    private final FinanceEventPublisher eventPublisher;

    public PaymentService(PaymentRepository paymentRepository,
                          AuditPublisher auditPublisher,
                          FinanceEventPublisher eventPublisher) {
        this.paymentRepository = paymentRepository;
        this.auditPublisher = auditPublisher;
        this.eventPublisher = eventPublisher;
    }

    public Flux<Payment> findAll() {
        return paymentRepository.findAllOrderByCreatedAtDesc();
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
    public Mono<Payment> create(CreatePaymentRequest request, String actorId) {
        return generatePaymentNumber()
            .flatMap(paymentNumber -> {
                var payment = new Payment();
                payment.setId(UUID.randomUUID());
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
                payment.setCreatedBy(UUID.fromString(actorId));
                payment.setUpdatedBy(UUID.fromString(actorId));

                return paymentRepository.save(payment);
            })
            .flatMap(saved -> Mono.deferContextual(ctx -> {
                String tenantId = TenantContext.get(ctx);
                return publishAudit(tenantId, "Payment", saved.getId().toString(), "CREATE", actorId,
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
    public Mono<Payment> markPaid(UUID id, String actorId) {
        return paymentRepository.findById(id)
            .switchIfEmpty(Mono.error(new PaymentNotFoundException(id)))
            .flatMap(payment -> {
                String previousStatus = payment.getStatus();
                payment.setStatus("paid");
                payment.setPaidAt(Instant.now());
                payment.setUpdatedAt(Instant.now());
                payment.setUpdatedBy(UUID.fromString(actorId));

                return paymentRepository.save(payment)
                    .flatMap(saved -> Mono.deferContextual(ctx -> {
                        String tenantId = TenantContext.get(ctx);
                        return publishAudit(tenantId, "Payment", saved.getId().toString(), "UPDATE", actorId,
                                Map.of("status", previousStatus),
                                Map.of("status", saved.getStatus()))
                            .thenReturn(saved);
                    }));
            });
    }

    @Transactional
    public Mono<Payment> cancel(UUID id, String actorId) {
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
                if (actorId != null) payment.setUpdatedBy(UUID.fromString(actorId));

                return paymentRepository.save(payment)
                    .flatMap(saved -> Mono.deferContextual(ctx -> {
                        String tenantId = TenantContext.get(ctx);
                        return publishAudit(tenantId, "Payment", saved.getId().toString(), "UPDATE", actorId,
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
