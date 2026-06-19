package com.medfund.finance.service;

import com.medfund.finance.dto.PaymentAdvice;
import com.medfund.finance.dto.PaymentAdvice.PaymentAdviceLine;
import com.medfund.finance.entity.PaymentAdviceRecord;
import com.medfund.finance.exception.PaymentNotFoundException;
import com.medfund.finance.repository.PaymentAdviceRecordRepository;
import com.medfund.finance.repository.PaymentRepository;
import com.medfund.finance.repository.PaymentRunItemRepository;
import com.medfund.finance.repository.PaymentRunRepository;
import reactor.core.publisher.Flux;
import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class PaymentAdviceService {

    private static final Logger log = LoggerFactory.getLogger(PaymentAdviceService.class);

    private final PaymentRunRepository paymentRunRepository;
    private final PaymentRunItemRepository paymentRunItemRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentAdviceRecordRepository adviceRepository;
    private final AuditPublisher auditPublisher;

    public PaymentAdviceService(PaymentRunRepository paymentRunRepository,
                                PaymentRunItemRepository paymentRunItemRepository,
                                PaymentRepository paymentRepository,
                                PaymentAdviceRecordRepository adviceRepository,
                                AuditPublisher auditPublisher) {
        this.paymentRunRepository = paymentRunRepository;
        this.paymentRunItemRepository = paymentRunItemRepository;
        this.paymentRepository = paymentRepository;
        this.adviceRepository = adviceRepository;
        this.auditPublisher = auditPublisher;
    }

    public Flux<PaymentAdviceRecord> findByRun(UUID paymentRunId) {
        return adviceRepository.findByPaymentRunId(paymentRunId);
    }

    public Flux<PaymentAdviceRecord> findByProvider(UUID providerId) {
        return adviceRepository.findByProviderId(providerId);
    }

    public Flux<PaymentAdviceRecord> findAll() {
        return adviceRepository.findAllOrdered();
    }

    public Mono<PaymentAdvice> generateAdvice(UUID paymentRunId) {
        return paymentRunRepository.findById(paymentRunId)
            .switchIfEmpty(Mono.error(new PaymentNotFoundException(paymentRunId)))
            .flatMap(run -> paymentRunItemRepository.findByPaymentRunId(paymentRunId)
                .flatMap(item -> paymentRepository.findById(item.getPaymentId())
                    .map(payment -> new PaymentAdviceLine(
                        payment.getPaymentNumber(),
                        "",  // memberName would be resolved via user-service lookup
                        payment.getAmount(),
                        payment.getAmount(),  // approvedAmount equals payment amount
                        item.getAmount(),
                        payment.getCreatedAt() != null ? payment.getCreatedAt().toString() : ""
                    ))
                )
                .collectList()
                .map(lines -> {
                    BigDecimal totalAmount = lines.stream()
                        .map(PaymentAdviceLine::paidAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

                    String adviceNumber = "ADV-" + ThreadLocalRandom.current().nextInt(100000, 999999);

                    return new PaymentAdvice(
                        adviceNumber,
                        null,  // providerId aggregated from run items
                        "",    // providerName would be resolved via user-service lookup
                        totalAmount,
                        run.getCurrencyCode(),
                        Instant.now(),
                        lines
                    );
                })
            )
            .flatMap(advice -> persistAdvice(paymentRunId, advice).thenReturn(advice))
            .flatMap(advice -> Mono.deferContextual(ctx -> {
                String tenantId = TenantContext.get(ctx);
                return publishAudit(tenantId, "PaymentAdvice", advice.adviceNumber(), "CREATE",
                        AuditActor.SYSTEM_ID, AuditActor.SYSTEM_EMAIL,
                        null,
                        Map.of("adviceNumber", advice.adviceNumber(),
                               "paymentRunId", paymentRunId.toString(),
                               "totalAmount", advice.totalAmount().toPlainString(),
                               "lineCount", String.valueOf(advice.lines().size())))
                    .thenReturn(advice);
            }));
    }

    private Mono<PaymentAdviceRecord> persistAdvice(UUID paymentRunId, PaymentAdvice advice) {
        var record = new PaymentAdviceRecord();
        record.setId(UUID.randomUUID());
        record.setPaymentRunId(paymentRunId);
        record.setProviderId(advice.providerId());
        record.setCurrencyCode(advice.currencyCode());
        record.setTotalAmount(advice.totalAmount());
        record.setClaimCount(advice.lines().size());
        record.setIssuedAt(advice.generatedAt() != null ? advice.generatedAt() : Instant.now());
        record.setStatus("generated");
        return adviceRepository.save(record);
    }

    // ---- Private helpers ----

    private Mono<Void> publishAudit(String tenantId, String entityType, String entityId,
                                     String action, String actorId, String actorEmail,
                                     Map<String, Object> oldValue, Map<String, Object> newValue) {
        var event = AuditEvent.create(
            tenantId != null ? tenantId : "unknown",
            entityType,
            entityId,
            action,
            actorId,
            actorEmail,
            oldValue,
            newValue,
            new String[]{},
            UUID.randomUUID().toString()
        );
        return auditPublisher.publish(event);
    }
}
