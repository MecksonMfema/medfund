package com.medfund.claims.service;

import com.medfund.claims.dto.QuotationRequest;
import com.medfund.claims.entity.Claim;
import com.medfund.claims.entity.ClaimLine;
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
import java.util.HashMap;
import java.util.List;
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
            .flatMap(q -> autoComputeCopay(q).flatMap(computed -> {
                q.setStatus("REVIEWED");
                q.setCoveredAmount(coveredAmount);
                q.setCoPaymentAmount(coPaymentAmount);
                q.setComputedCoPaymentAmount(computed);
                q.setNotes(notes);
                q.setReviewedBy(UUID.fromString(actorId));
                q.setReviewedAt(Instant.now());
                q.setUpdatedAt(Instant.now());
                return quotationRepository.save(q);
            }))
            .flatMap(saved -> Mono.deferContextual(ctx -> {
                String tenantId = TenantContext.get(ctx);
                // Record BOTH the computed and the reviewer-entered override so the
                // audit trail shows an operator-set value that diverged from the
                // system suggestion (Phase 2 quotation-review override warning).
                Map<String, Object> newValue = new HashMap<>();
                newValue.put("status", "REVIEWED");
                newValue.put("coveredAmount",
                        saved.getCoveredAmount() != null ? saved.getCoveredAmount().toPlainString() : "");
                newValue.put("coPaymentAmount",
                        saved.getCoPaymentAmount() != null ? saved.getCoPaymentAmount().toPlainString() : "");
                newValue.put("computedCoPaymentAmount",
                        saved.getComputedCoPaymentAmount() != null ? saved.getComputedCoPaymentAmount().toPlainString() : "");
                var event = AuditEvent.create(
                    tenantId != null ? tenantId : "unknown",
                    "Quotation", saved.getId().toString(), saved.getQuotationNumber(),
                    "UPDATE", actorId, actorEmail,
                    Map.of("status", "PENDING"),
                    newValue,
                    new String[]{"status", "coveredAmount", "coPaymentAmount", "computedCoPaymentAmount"},
                    UUID.randomUUID().toString()
                );
                return auditPublisher.publish(event).thenReturn(saved);
            }));
    }

    /**
     * Auto-compute the copay a reviewer sees before entering their override.
     * A quotation has no persisted lines so we synthesise a single-line claim
     * from the {@code estimatedAmount} and hand it to {@link CoPaymentService}
     * — same math a submitted claim would run at Stage 5, minus the tariff
     * lookup (the quote is coarser than a real claim by design). Errors and
     * missing tariffs fall through to zero rather than blocking review.
     */
    private Mono<BigDecimal> autoComputeCopay(Quotation q) {
        if (q.getEstimatedAmount() == null || q.getEstimatedAmount().signum() <= 0) {
            return Mono.just(BigDecimal.ZERO);
        }
        Claim synthetic = new Claim();
        synthetic.setId(q.getId());
        synthetic.setMemberId(q.getMemberId());
        synthetic.setProviderId(q.getProviderId());
        synthetic.setSchemeId(q.getSchemeId());
        synthetic.setClaimedAmount(q.getEstimatedAmount());
        synthetic.setCurrencyCode(q.getCurrencyCode());

        ClaimLine line = new ClaimLine();
        line.setId(UUID.randomUUID());
        line.setClaimId(q.getId());
        line.setTariffCode(q.getProcedureCodes() != null ? q.getProcedureCodes() : "QUOTE");
        line.setQuantity(1);
        line.setClaimedAmount(q.getEstimatedAmount());
        line.setUnitPrice(q.getEstimatedAmount());
        line.setCurrencyCode(q.getCurrencyCode());

        return coPaymentService.calculate(synthetic, List.of(line))
                .map(r -> r.totalCoPayment() != null ? r.totalCoPayment() : BigDecimal.ZERO)
                .onErrorResume(e -> {
                    log.debug("Quotation copay auto-compute skipped: {}", e.getMessage());
                    return Mono.just(BigDecimal.ZERO);
                });
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
