package com.medfund.contributions.service;

import com.medfund.contributions.dto.BillingCommitResponse;
import com.medfund.contributions.dto.BillingPreviewResponse;
import com.medfund.contributions.dto.CommitBillingRequest;
import com.medfund.contributions.dto.GenerateBillingRequest;
import com.medfund.contributions.dto.PreviewBillingRequest;
import com.medfund.contributions.entity.BillingCycleConfig;
import com.medfund.contributions.entity.Contribution;
import com.medfund.contributions.entity.Invoice;
import com.medfund.contributions.exception.BillingCooldownException;
import com.medfund.contributions.exception.ContributionNotFoundException;
import com.medfund.contributions.exception.InvoiceNotFoundException;
import com.medfund.contributions.repository.AgeGroupRepository;
import com.medfund.contributions.repository.BillingCycleConfigRepository;
import com.medfund.contributions.repository.ContributionRepository;
import com.medfund.contributions.repository.InvoiceRepository;
import com.medfund.contributions.repository.SchemeRepository;
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

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class BillingService {

    private static final Logger log = LoggerFactory.getLogger(BillingService.class);

    private static final int SAMPLE_LIMIT = 25;

    private final ContributionRepository contributionRepository;
    private final InvoiceRepository invoiceRepository;
    private final SchemeRepository schemeRepository;
    private final AgeGroupRepository ageGroupRepository;
    private final BillingCycleConfigRepository billingCycleConfigRepository;
    private final AuditPublisher auditPublisher;
    private final ContributionEventPublisher eventPublisher;
    private final ContributionPricingService pricingService;
    private final BalanceService balanceService;
    private final DatabaseClient db;

    public BillingService(ContributionRepository contributionRepository,
                          InvoiceRepository invoiceRepository,
                          SchemeRepository schemeRepository,
                          AgeGroupRepository ageGroupRepository,
                          BillingCycleConfigRepository billingCycleConfigRepository,
                          AuditPublisher auditPublisher,
                          ContributionEventPublisher eventPublisher,
                          ContributionPricingService pricingService,
                          BalanceService balanceService,
                          DatabaseClient db) {
        this.contributionRepository = contributionRepository;
        this.invoiceRepository = invoiceRepository;
        this.schemeRepository = schemeRepository;
        this.ageGroupRepository = ageGroupRepository;
        this.billingCycleConfigRepository = billingCycleConfigRepository;
        this.auditPublisher = auditPublisher;
        this.eventPublisher = eventPublisher;
        this.pricingService = pricingService;
        this.balanceService = balanceService;
        this.db = db;
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
        return schemeRepository.findById(request.schemeId())
            .switchIfEmpty(Mono.error(new IllegalArgumentException(
                "Scheme not found: " + request.schemeId())))
            .flatMap(scheme -> {
                String currency = (request.currencyCode() == null || request.currencyCode().isBlank())
                    ? schemeCurrencyOrUsd(scheme.getCurrencyCode())
                    : request.currencyCode();
                if (scheme.getCurrencyCode() != null && !scheme.getCurrencyCode().isBlank()
                    && !scheme.getCurrencyCode().equalsIgnoreCase(currency)) {
                    return Mono.<Contribution>error(new IllegalArgumentException(
                        "Contribution currency '" + currency
                            + "' does not match scheme currency '" + scheme.getCurrencyCode() + "'"));
                }
                var contribution = new Contribution();
                contribution.setId(UUID.randomUUID());
                contribution.setSchemeId(request.schemeId());
                contribution.setGroupId(request.groupId());
                contribution.setCurrencyCode(currency);
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
                    .then(Mono.defer(() -> contributionRepository.save(contribution)));
            })
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
                    .flatMap(saved -> balanceService.applyContributionPaid(saved).thenReturn(saved))
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

    private static String schemeCurrencyOrUsd(String schemeCurrency) {
        return (schemeCurrency == null || schemeCurrency.isBlank()) ? "USD" : schemeCurrency;
    }

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

    // ── Wizard: preview / commit ──────────────────────────────────────────────

    /**
     * Pure read — selects the population the commit would bill, runs the
     * tenant pricing rules, and returns counts/totals. No persistence.
     */
    public Mono<BillingPreviewResponse> previewBilling(PreviewBillingRequest req) {
        return resolveCandidates(req.schemeIds(), req.groupIds(), req.memberIds())
                .flatMap(candidate -> applyPricing(candidate, req.periodStart(), req.periodEnd()))
                .collectList()
                .zipWith(billingCycleConfigRepository.findById(BillingCycleConfig.SINGLETON_ID)
                        .defaultIfEmpty(defaultCycleConfig()))
                .map(tuple -> {
                    List<PricedCandidate> rows = tuple.getT1();
                    BillingCycleConfig cfg = tuple.getT2();
                    Map<String, BigDecimal> totals = new LinkedHashMap<>();
                    for (PricedCandidate r : rows) {
                        totals.merge(r.currencyCode(), r.amount(), BigDecimal::add);
                    }
                    List<BillingPreviewResponse.SampleRow> sample = new ArrayList<>();
                    for (int i = 0; i < Math.min(rows.size(), SAMPLE_LIMIT); i++) {
                        PricedCandidate r = rows.get(i);
                        sample.add(new BillingPreviewResponse.SampleRow(
                                r.memberId(), r.memberNumber(), r.schemeId(), r.schemeName(),
                                r.groupId(), r.amount(), r.currencyCode()));
                    }
                    int remainingMinutes = cooldownRemainingMinutes(cfg);
                    return new BillingPreviewResponse(
                            rows.size(), totals, sample,
                            remainingMinutes > 0,
                            remainingMinutes > 0 ? remainingMinutes : null);
                });
    }

    /**
     * Persists contribution rows for the same selection. Honours
     * {@code billing_cycle_config.commit_cooldown_hours}; double-commits
     * inside that window throw {@link BillingCooldownException}.
     */
    @Transactional
    public Mono<BillingCommitResponse> commitBilling(CommitBillingRequest req, String actorId) {
        return billingCycleConfigRepository.findById(BillingCycleConfig.SINGLETON_ID)
                .defaultIfEmpty(defaultCycleConfig())
                .flatMap(cfg -> {
                    int remaining = cooldownRemainingMinutes(cfg);
                    if (remaining > 0) {
                        return Mono.<BillingCommitResponse>error(new BillingCooldownException(remaining));
                    }
                    return doCommit(req, actorId, cfg);
                });
    }

    private Mono<BillingCommitResponse> doCommit(CommitBillingRequest req, String actorId, BillingCycleConfig cfg) {
        UUID actorUuid = parseUuid(actorId);
        Instant now = Instant.now();

        return resolveCandidates(req.schemeIds(), req.groupIds(), req.memberIds())
                .flatMap(candidate -> applyPricing(candidate, req.periodStart(), req.periodEnd())
                        .flatMap(priced -> persistContribution(priced, req, actorUuid, now)))
                .collectList()
                .flatMap(saved -> {
                    Map<String, BigDecimal> totals = new LinkedHashMap<>();
                    for (Contribution c : saved) {
                        totals.merge(c.getCurrencyCode(),
                                c.getAmount() != null ? c.getAmount() : BigDecimal.ZERO,
                                BigDecimal::add);
                    }
                    Mono<Void> stamp = billingCycleConfigRepository
                            .updateLastCommittedAt(cfg.getId() != null ? cfg.getId() : BillingCycleConfig.SINGLETON_ID, now)
                            .then();
                    Mono<Void> publish = eventPublisher.publishBillingGenerated(
                            req.schemeIds() != null && !req.schemeIds().isEmpty()
                                    ? req.schemeIds().get(0).toString() : "*",
                            req.periodStart().toString(),
                            req.periodEnd().toString(),
                            saved.size()).then();
                    Mono<Void> audit = Mono.deferContextual(ctx -> publishAudit(
                            TenantContext.get(ctx), "BillingCycle", "commit", "CREATE", actorId,
                            null,
                            Map.of("rows", String.valueOf(saved.size()),
                                    "periodStart", req.periodStart().toString(),
                                    "periodEnd", req.periodEnd().toString())));
                    return stamp.then(publish).then(audit)
                            .thenReturn(new BillingCommitResponse((long) saved.size(), totals, now));
                });
    }

    private Mono<Contribution> persistContribution(PricedCandidate priced, CommitBillingRequest req,
                                                   UUID actorUuid, Instant now) {
        Contribution c = new Contribution();
        c.setId(UUID.randomUUID());
        c.setMemberId(priced.memberId());
        c.setSchemeId(priced.schemeId());
        c.setGroupId(priced.groupId());
        c.setAmount(priced.amount());
        c.setCurrencyCode(priced.currencyCode());
        c.setPeriodStart(req.periodStart());
        c.setPeriodEnd(req.periodEnd());
        c.setStatus("pending");
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        c.setCreatedBy(actorUuid);
        c.setUpdatedBy(actorUuid);
        return contributionRepository.save(c)
                .flatMap(saved -> balanceService.applyContributionDebit(saved).thenReturn(saved));
    }

    /**
     * Walks the {@code members} table joined to {@code schemes}, applying the
     * three optional filters. Returned candidates carry just enough metadata
     * for pricing + the wizard preview.
     */
    private Flux<MemberCandidate> resolveCandidates(List<UUID> schemeIds, List<UUID> groupIds, List<UUID> memberIds) {
        StringBuilder sql = new StringBuilder("""
                SELECT m.id            AS member_id,
                       m.member_number AS member_number,
                       m.scheme_id     AS scheme_id,
                       m.group_id      AS group_id,
                       s.name          AS scheme_name,
                       s.currency_code AS scheme_currency
                  FROM members m
                  JOIN schemes s ON s.id = m.scheme_id
                 WHERE m.status = 'active'
                """);
        if (schemeIds != null && !schemeIds.isEmpty()) sql.append(" AND m.scheme_id = ANY(:schemeIds) ");
        if (groupIds  != null && !groupIds.isEmpty())  sql.append(" AND m.group_id  = ANY(:groupIds) ");
        if (memberIds != null && !memberIds.isEmpty()) sql.append(" AND m.id        = ANY(:memberIds) ");

        var spec = db.sql(sql.toString());
        if (schemeIds != null && !schemeIds.isEmpty()) spec = spec.bind("schemeIds", schemeIds.toArray(UUID[]::new));
        if (groupIds  != null && !groupIds.isEmpty())  spec = spec.bind("groupIds",  groupIds.toArray(UUID[]::new));
        if (memberIds != null && !memberIds.isEmpty()) spec = spec.bind("memberIds", memberIds.toArray(UUID[]::new));

        return spec.map(row -> new MemberCandidate(
                        (UUID) row.get("member_id"),
                        (String) row.get("member_number"),
                        (UUID) row.get("scheme_id"),
                        (String) row.get("scheme_name"),
                        (UUID) row.get("group_id"),
                        (String) row.get("scheme_currency")))
                .all();
    }

    /**
     * Build a transient Contribution → run pricing rules → emit a priced
     * candidate. The transient row is never saved here; preview and commit
     * decide whether to persist.
     */
    private Mono<PricedCandidate> applyPricing(MemberCandidate m, LocalDate periodStart, LocalDate periodEnd) {
        Contribution transient_ = new Contribution();
        transient_.setMemberId(m.memberId());
        transient_.setSchemeId(m.schemeId());
        transient_.setGroupId(m.groupId());
        transient_.setCurrencyCode(m.schemeCurrency() != null ? m.schemeCurrency() : "USD");
        transient_.setPeriodStart(periodStart);
        transient_.setPeriodEnd(periodEnd);
        transient_.setStatus("pending");

        // pricingService.price() returns the rule fact for late-fee tracking;
        // the priced amount itself is written back onto the contribution row.
        return pricingService.price(transient_)
                .thenReturn(new PricedCandidate(
                        m.memberId(), m.memberNumber(),
                        m.schemeId(), m.schemeName(), m.groupId(),
                        transient_.getAmount() != null ? transient_.getAmount() : BigDecimal.ZERO,
                        transient_.getCurrencyCode()));
    }

    private static int cooldownRemainingMinutes(BillingCycleConfig cfg) {
        if (cfg.getLastCommittedAt() == null) return 0;
        short hours = cfg.getCommitCooldownHours() != null ? cfg.getCommitCooldownHours() : (short) 0;
        if (hours <= 0) return 0;
        Duration elapsed = Duration.between(cfg.getLastCommittedAt(), Instant.now());
        long remaining = Duration.ofHours(hours).toMinutes() - elapsed.toMinutes();
        return remaining > 0 ? (int) remaining : 0;
    }

    private static BillingCycleConfig defaultCycleConfig() {
        BillingCycleConfig fallback = new BillingCycleConfig();
        fallback.setId(BillingCycleConfig.SINGLETON_ID);
        fallback.setCommitCooldownHours((short) 0);
        return fallback;
    }

    private static UUID parseUuid(String s) {
        try { return s != null ? UUID.fromString(s) : null; } catch (IllegalArgumentException e) { return null; }
    }

    private record MemberCandidate(UUID memberId, String memberNumber, UUID schemeId, String schemeName,
                                    UUID groupId, String schemeCurrency) {}

    private record PricedCandidate(UUID memberId, String memberNumber, UUID schemeId, String schemeName,
                                    UUID groupId, BigDecimal amount, String currencyCode) {}

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
