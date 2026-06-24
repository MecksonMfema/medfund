package com.medfund.claims.service;

import com.medfund.claims.dto.QuotationRequest;
import com.medfund.claims.entity.Quotation;
import com.medfund.claims.repository.QuotationRepository;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class QuotationService {

    private static final Logger log = LoggerFactory.getLogger(QuotationService.class);

    private final QuotationRepository quotationRepository;
    private final CoPaymentService coPaymentService;
    private final AuditPublisher auditPublisher;

    public QuotationService(QuotationRepository quotationRepository,
                            CoPaymentService coPaymentService,
                            AuditPublisher auditPublisher) {
        this.quotationRepository = quotationRepository;
        this.coPaymentService = coPaymentService;
        this.auditPublisher = auditPublisher;
    }

    public Flux<Quotation> findAll() {
        return quotationRepository.findByStatus("PENDING");
    }

    public Mono<Quotation> findById(UUID id) {
        return quotationRepository.findById(id);
    }

    public Flux<Quotation> findByMemberId(UUID memberId) {
        return quotationRepository.findByMemberId(memberId);
    }

    public Flux<Quotation> findByProviderId(UUID providerId) {
        return quotationRepository.findByProviderId(providerId);
    }

    public Flux<Quotation> findByStatus(String status) {
        return quotationRepository.findByStatus(status);
    }

    @Transactional
    public Mono<Quotation> submit(QuotationRequest request, String actorId, String actorEmail) {
        return generateQuotationNumber()
            .flatMap(number -> {
                var q = new Quotation();
                q.setQuotationNumber(number);
                q.setMemberId(request.memberId());
                q.setProviderId(request.providerId());
                q.setSchemeId(request.schemeId());
                q.setDiagnosisCode(request.diagnosisCode());
                q.setProcedureCodes(request.procedureCodes());
                q.setDescription(request.description());
                q.setEstimatedAmount(request.estimatedAmount());
                q.setCurrencyCode(request.currencyCodeOrDefault());
                q.setStatus("PENDING");
                q.setValidUntil(LocalDate.now().plusDays(30));
                q.setNotes(request.notes());
                q.setCreatedAt(Instant.now());
                q.setUpdatedAt(Instant.now());
                q.setCreatedBy(UUID.fromString(actorId));

                return quotationRepository.save(q);
            })
            .flatMap(saved -> Mono.deferContextual(ctx -> {
                String tenantId = TenantContext.get(ctx);
                var event = AuditEvent.create(
                    tenantId != null ? tenantId : "unknown",
                    "Quotation", saved.getId().toString(), saved.getQuotationNumber(),
                    "CREATE", actorId, actorEmail, null,
                    Map.of("quotationNumber", saved.getQuotationNumber(),
                           "estimatedAmount", saved.getEstimatedAmount().toPlainString()),
                    new String[]{"quotationNumber", "estimatedAmount", "status"},
                    UUID.randomUUID().toString()
                );
                return auditPublisher.publish(event).thenReturn(saved);
            }));
    }

    @Transactional
    public Mono<Quotation> review(UUID id, BigDecimal coveredAmount, BigDecimal coPaymentAmount,
                                   String notes, String actorId, String actorEmail) {
        return quotationRepository.findById(id)
            .flatMap(q -> {
                q.setStatus("REVIEWED");
                q.setCoveredAmount(coveredAmount);
                q.setCoPaymentAmount(coPaymentAmount);
                q.setNotes(notes);
                q.setReviewedBy(UUID.fromString(actorId));
                q.setReviewedAt(Instant.now());
                q.setUpdatedAt(Instant.now());

                return quotationRepository.save(q);
            })
            .flatMap(saved -> Mono.deferContextual(ctx -> {
                String tenantId = TenantContext.get(ctx);
                var event = AuditEvent.create(
                    tenantId != null ? tenantId : "unknown",
                    "Quotation", saved.getId().toString(), saved.getQuotationNumber(),
                    "UPDATE", actorId, actorEmail,
                    Map.of("status", "PENDING"),
                    Map.of("status", "REVIEWED", "coveredAmount", saved.getCoveredAmount().toPlainString()),
                    new String[]{"status", "coveredAmount", "coPaymentAmount"},
                    UUID.randomUUID().toString()
                );
                return auditPublisher.publish(event).thenReturn(saved);
            }));
    }

    @Transactional
    public Mono<Quotation> approve(UUID id, String actorId) {
        return quotationRepository.findById(id)
            .flatMap(q -> {
                q.setStatus("APPROVED");
                q.setUpdatedAt(Instant.now());
                return quotationRepository.save(q);
            });
    }

    @Transactional
    public Mono<Quotation> reject(UUID id, String reason, String actorId) {
        return quotationRepository.findById(id)
            .flatMap(q -> {
                q.setStatus("REJECTED");
                q.setRejectionReason(reason);
                q.setUpdatedAt(Instant.now());
                return quotationRepository.save(q);
            });
    }

    private Mono<String> generateQuotationNumber() {
        String number = "QUO-" + ThreadLocalRandom.current().nextInt(100000, 999999);
        return quotationRepository.existsByQuotationNumber(number)
            .flatMap(exists -> exists ? generateQuotationNumber() : Mono.just(number));
    }
}
