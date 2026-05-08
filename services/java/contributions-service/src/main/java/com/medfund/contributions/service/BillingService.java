package com.medfund.contributions.service;

import com.medfund.contributions.dto.GenerateBillingRequest;
import com.medfund.contributions.entity.Contribution;
import com.medfund.contributions.entity.Invoice;
import com.medfund.contributions.exception.ContributionNotFoundException;
import com.medfund.contributions.exception.InvoiceNotFoundException;
import com.medfund.contributions.repository.AgeGroupRepository;
import com.medfund.contributions.repository.ContributionRepository;
import com.medfund.contributions.repository.InvoiceRepository;
import com.medfund.contributions.repository.SchemeRepository;
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
public class BillingService {

    private static final Logger log = LoggerFactory.getLogger(BillingService.class);

    private final ContributionRepository contributionRepository;
    private final InvoiceRepository invoiceRepository;
    private final SchemeRepository schemeRepository;
    private final AgeGroupRepository ageGroupRepository;
    private final AuditPublisher auditPublisher;
    private final ContributionEventPublisher eventPublisher;
    private final ContributionPricingService pricingService;

    public BillingService(ContributionRepository contributionRepository,
                          InvoiceRepository invoiceRepository,
                          SchemeRepository schemeRepository,
                          AgeGroupRepository ageGroupRepository,
                          AuditPublisher auditPublisher,
                          ContributionEventPublisher eventPublisher,
                          ContributionPricingService pricingService) {
        this.contributionRepository = contributionRepository;
        this.invoiceRepository = invoiceRepository;
        this.schemeRepository = schemeRepository;
        this.ageGroupRepository = ageGroupRepository;
        this.auditPublisher = auditPublisher;
        this.eventPublisher = eventPublisher;
        this.pricingService = pricingService;
    }

    public Flux<Contribution> findContributionsByMemberId(UUID memberId) {
        return contributionRepository.findByMemberId(memberId);
    }

    public Flux<Contribution> findContributionsByGroupId(UUID groupId) {
        return contributionRepository.findByGroupId(groupId);
    }

    public Flux<Contribution> findContributionsByStatus(String status) {
        return contributionRepository.findByStatus(status);
    }

    public Mono<Contribution> findContributionById(UUID id) {
        return contributionRepository.findById(id)
            .switchIfEmpty(Mono.error(new ContributionNotFoundException(id)));
    }

    @Transactional
    public Mono<Long> generateBilling(GenerateBillingRequest request, String actorId) {
        var contribution = new Contribution();
        contribution.setId(UUID.randomUUID());
        contribution.setSchemeId(request.schemeId());
        contribution.setGroupId(request.groupId());
        contribution.setCurrencyCode(request.currencyCode());
        contribution.setPeriodStart(request.periodStart());
        contribution.setPeriodEnd(request.periodEnd());
        contribution.setStatus("pending");
        contribution.setCreatedAt(Instant.now());
        contribution.setUpdatedAt(Instant.now());
        contribution.setCreatedBy(UUID.fromString(actorId));
        contribution.setUpdatedBy(UUID.fromString(actorId));

        // Run tenant pricing rules before persistence. The pricing service
        // mutates contribution.amount in place when SET_PREMIUM /
        // APPLY_LOADED_PREMIUM rules fire; tenants without pricing rules
        // get the legacy behaviour (whatever amount the request supplied).
        return pricingService.price(contribution)
            .then(Mono.defer(() -> contributionRepository.save(contribution)))
            .flatMap(saved -> Mono.deferContextual(ctx -> {
                String tenantId = TenantContext.get(ctx);
                return publishAudit(tenantId, "Contribution", saved.getId().toString(), "CREATE", actorId,
                        null,
                        Map.of("status", saved.getStatus(),
                               "schemeId", saved.getSchemeId().toString(),
                               "periodStart", saved.getPeriodStart().toString(),
                               "periodEnd", saved.getPeriodEnd().toString()))
                    .then(eventPublisher.publishBillingGenerated(
                        saved.getSchemeId().toString(),
                        saved.getPeriodStart().toString(),
                        saved.getPeriodEnd().toString(),
                        1))
                    .thenReturn(1L);
            }));
    }

    @Transactional
    public Mono<Contribution> recordPayment(UUID contributionId, String paymentMethod,
                                             String paymentReference, String actorId) {
        return contributionRepository.findById(contributionId)
            .switchIfEmpty(Mono.error(new ContributionNotFoundException(contributionId)))
            .flatMap(contribution -> {
                String previousStatus = contribution.getStatus();
                contribution.setStatus("paid");
                contribution.setPaymentMethod(paymentMethod);
                contribution.setPaymentReference(paymentReference);
                contribution.setPaidAt(Instant.now());
                contribution.setUpdatedAt(Instant.now());
                contribution.setUpdatedBy(UUID.fromString(actorId));

                return contributionRepository.save(contribution)
                    .flatMap(saved -> Mono.deferContextual(ctx -> {
                        String tenantId = TenantContext.get(ctx);
                        return publishAudit(tenantId, "Contribution", saved.getId().toString(), "UPDATE", actorId,
                                Map.of("status", previousStatus),
                                Map.of("status", saved.getStatus(),
                                       "paymentMethod", saved.getPaymentMethod(),
                                       "paymentReference", saved.getPaymentReference()))
                            .then(eventPublisher.publishContributionPaid(
                                saved.getId().toString(),
                                saved.getMemberId() != null ? saved.getMemberId().toString() : "",
                                saved.getAmount() != null ? saved.getAmount().toString() : ""))
                            .thenReturn(saved);
                    }));
            });
    }

    public Flux<Invoice> findInvoicesByGroupId(UUID groupId) {
        return invoiceRepository.findByGroupId(groupId);
    }

    public Flux<Invoice> findInvoicesByMemberId(UUID memberId) {
        return invoiceRepository.findByMemberId(memberId);
    }

    public Mono<Invoice> findInvoiceById(UUID id) {
        return invoiceRepository.findById(id)
            .switchIfEmpty(Mono.error(new InvoiceNotFoundException(id)));
    }

    @Transactional
    public Mono<Invoice> generateInvoice(UUID groupId, UUID schemeId, LocalDate periodStart,
                                          LocalDate periodEnd, BigDecimal totalAmount,
                                          String currencyCode, String actorId) {
        return generateInvoiceNumber()
            .flatMap(invoiceNumber -> {
                var invoice = new Invoice();
                invoice.setId(UUID.randomUUID());
                invoice.setInvoiceNumber(invoiceNumber);
                invoice.setGroupId(groupId);
                invoice.setSchemeId(schemeId);
                invoice.setTotalAmount(totalAmount);
                invoice.setCurrencyCode(currencyCode);
                invoice.setStatus("issued");
                invoice.setPeriodStart(periodStart);
                invoice.setPeriodEnd(periodEnd);
                invoice.setIssuedAt(Instant.now());
                invoice.setDueDate(periodEnd.plusDays(30));
                invoice.setCreatedAt(Instant.now());
                invoice.setUpdatedAt(Instant.now());
                invoice.setCreatedBy(UUID.fromString(actorId));

                return invoiceRepository.save(invoice);
            })
            .flatMap(saved -> Mono.deferContextual(ctx -> {
                String tenantId = TenantContext.get(ctx);
                return publishAudit(tenantId, "Invoice", saved.getId().toString(), "CREATE", actorId,
                        null,
                        Map.of("invoiceNumber", saved.getInvoiceNumber(),
                               "status", saved.getStatus(),
                               "totalAmount", saved.getTotalAmount().toString(),
                               "groupId", saved.getGroupId().toString()))
                    .then(eventPublisher.publishInvoiceIssued(
                        saved.getId().toString(),
                        saved.getInvoiceNumber(),
                        saved.getGroupId().toString()))
                    .thenReturn(saved);
            }));
    }

    @Transactional
    public Mono<Contribution> createInitialContribution(UUID memberId, UUID groupId) {
        log.info("Creating initial contribution for member: {}, group: {}", memberId, groupId);

        var contribution = new Contribution();
        contribution.setId(UUID.randomUUID());
        contribution.setMemberId(memberId);
        contribution.setGroupId(groupId);
        contribution.setStatus("pending");
        contribution.setCreatedAt(Instant.now());
        contribution.setUpdatedAt(Instant.now());

        return contributionRepository.save(contribution)
            .flatMap(saved -> Mono.deferContextual(ctx -> {
                String tenantId = TenantContext.get(ctx);
                return publishAudit(tenantId, "Contribution", saved.getId().toString(), "CREATE", "system",
                        null,
                        Map.of("memberId", memberId.toString(),
                               "groupId", groupId != null ? groupId.toString() : "",
                               "status", saved.getStatus()))
                    .thenReturn(saved);
            }));
    }

    // ---- Private helpers ----

    private Mono<String> generateInvoiceNumber() {
        String number = "INV-" + String.format("%06d", ThreadLocalRandom.current().nextInt(0, 999999));
        return invoiceRepository.existsByInvoiceNumber(number)
            .flatMap(exists -> exists ? generateInvoiceNumber() : Mono.just(number));
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
            new String[]{},
            UUID.randomUUID().toString()
        );
        return auditPublisher.publish(event);
    }

    public Mono<Void> markOverdueContributions() {
        return contributionRepository.findByStatus("pending")
            .filter(c -> c.getPeriodEnd() != null && c.getPeriodEnd().isBefore(java.time.LocalDate.now()))
            .flatMap(c -> {
                c.setStatus("overdue");
                c.setUpdatedAt(java.time.Instant.now());
                return contributionRepository.save(c);
            })
            .then();
    }

    public Mono<Void> runAutoBilling() {
        log.info("Auto billing cycle triggered — stub implementation");
        return Mono.empty();
    }
}
