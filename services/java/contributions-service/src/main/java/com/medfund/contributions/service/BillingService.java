package com.medfund.contributions.service;

import com.medfund.contributions.dto.BillingCommitResponse;
import com.medfund.contributions.dto.BillingPreviewResponse;
import com.medfund.contributions.dto.CommitBillingRequest;
import com.medfund.contributions.dto.GenerateBillingRequest;
import com.medfund.contributions.dto.PreviewBillingRequest;
import com.medfund.contributions.entity.BillingCycleConfig;
import com.medfund.contributions.entity.Contribution;
import com.medfund.contributions.entity.Invoice;
import com.medfund.contributions.dto.BillingRevokeResponse;
import com.medfund.contributions.dto.RevokeBillingRequest;
import com.medfund.contributions.exception.BillingCooldownException;
import com.medfund.contributions.exception.BillingNotRevocableException;
import com.medfund.contributions.exception.BillingPeriodAlreadyCommittedException;
import com.medfund.contributions.exception.ContributionNotFoundException;
import com.medfund.contributions.exception.InvoiceNotFoundException;
import com.medfund.contributions.repository.AgeGroupRepository;
import com.medfund.contributions.repository.BillingCycleConfigRepository;
import com.medfund.contributions.repository.ContributionRepository;
import com.medfund.contributions.repository.InvoiceRepository;
import com.medfund.contributions.repository.SchemeRepository;
import com.medfund.contributions.service.candidate.PersonCandidate;
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
    private final AiPricingClient aiPricingClient;
    private final InvoiceSnapshotService invoiceSnapshotService;
    /**
     * Per-insurance-line candidate resolvers, keyed by
     * {@link com.medfund.contributions.service.candidate.CandidateResolver#supportedLine()}.
     * Spring auto-collects every {@code @Component} implementation —
     * adding a new line is "drop one class" — no edit here.
     */
    private final java.util.Map<String, com.medfund.contributions.service.candidate.CandidateResolver> candidateResolvers;

    public BillingService(ContributionRepository contributionRepository,
                          InvoiceRepository invoiceRepository,
                          SchemeRepository schemeRepository,
                          AgeGroupRepository ageGroupRepository,
                          BillingCycleConfigRepository billingCycleConfigRepository,
                          AuditPublisher auditPublisher,
                          ContributionEventPublisher eventPublisher,
                          ContributionPricingService pricingService,
                          BalanceService balanceService,
                          DatabaseClient db,
                          AiPricingClient aiPricingClient,
                          InvoiceSnapshotService invoiceSnapshotService,
                          java.util.List<com.medfund.contributions.service.candidate.CandidateResolver> resolvers) {
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
        this.aiPricingClient = aiPricingClient;
        this.invoiceSnapshotService = invoiceSnapshotService;
        // Tolerant of a null/empty resolver list so unit tests that mock
        // the service without spinning up the Spring context still work.
        // In production Spring auto-collects every @Component
        // CandidateResolver into this list.
        java.util.List<com.medfund.contributions.service.candidate.CandidateResolver> safeResolvers =
                resolvers != null ? resolvers : java.util.List.of();
        this.candidateResolvers = safeResolvers.stream()
                .collect(java.util.stream.Collectors.toMap(
                        com.medfund.contributions.service.candidate.CandidateResolver::supportedLine,
                        r -> r));
    }

    public Flux<Contribution> findContributionsByMemberId(UUID memberId) {
        return contributionRepository.findByMemberId(memberId);
    }

    /**
     * Look up the {@code invoice_pdfs} pointer for an invoice. Returns
     * empty when the PDF hasn't been rendered yet (the
     * InvoicePdfReadyConsumer hasn't received its Kafka event). The
     * caller surfaces that as a 404 with "PDF not yet rendered".
     */
    public Mono<com.medfund.contributions.controller.InvoiceController.PdfPointer> findPdfPointer(UUID invoiceId) {
        return db.sql("""
                SELECT p.bucket, p.object_key, i.invoice_number
                  FROM invoice_pdfs p
                  JOIN invoices i ON i.id = p.invoice_id
                 WHERE p.invoice_id = :id
                """)
                .bind("id", invoiceId)
                .map(row -> new com.medfund.contributions.controller.InvoiceController.PdfPointer(
                        row.get("bucket", String.class),
                        row.get("object_key", String.class),
                        row.get("invoice_number", String.class)))
                .one();
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
    public Mono<Long> generateBilling(GenerateBillingRequest request, String actorId, String actorEmail) {
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
                return publishAudit(tenantId, "Contribution", saved.getId().toString(),
                        contributionName(saved),
                        "CREATE", actorId, actorEmail,
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
                                             String paymentReference, String actorId, String actorEmail) {
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
                        return publishAudit(tenantId, "Contribution", saved.getId().toString(),
                                contributionName(saved),
                                "UPDATE", actorId, actorEmail,
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
                                          String currencyCode, String actorId, String actorEmail) {
        return generateInvoiceNumber()
            .flatMap(invoiceNumber -> {
                var invoice = new Invoice();
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
                return publishAudit(tenantId, "Invoice", saved.getId().toString(), saved.getInvoiceNumber(),
                        "CREATE", actorId, actorEmail,
                        null,
                        Map.of("invoiceNumber", saved.getInvoiceNumber(),
                               "status", saved.getStatus(),
                               "totalAmount", saved.getTotalAmount().toString(),
                               "groupId", saved.getGroupId().toString()))
                    .then(eventPublisher.publishInvoiceIssued(
                        new ContributionEventPublisher.InvoiceIssuedPayload(
                            saved.getId().toString(),
                            saved.getInvoiceNumber(),
                            tenantId,
                            saved.getGroupId() != null  ? saved.getGroupId().toString()  : null,
                            saved.getMemberId() != null ? saved.getMemberId().toString() : null,
                            saved.getCurrencyCode(),
                            saved.getTotalAmount().toPlainString(),
                            saved.getPeriodStart().toString(),
                            saved.getPeriodEnd().toString(),
                            saved.getDueDate().toString(),
                            // Legacy single-invoice path — predates snapshot
                            // capture. Pass null for snapshot fields; the
                            // file-service template treats nulls as "balance
                            // figures unavailable" per the legacy-row note.
                            // recipientName also null; renderer falls back.
                            null, null, null, null, null, null)))
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
            new String[]{},
            UUID.randomUUID().toString()
        );
        return auditPublisher.publish(event);
    }

    /**
     * Build a human-readable label for a contribution row — contributions have
     * no number/code field of their own, so we synthesize one from the member
     * and billing period (e.g. "member 7f4a... 2026-01-01..2026-01-31").
     */
    private static String contributionName(Contribution c) {
        String member = c.getMemberId() != null ? c.getMemberId().toString() : "?";
        String period = c.getPeriodStart() != null
                ? c.getPeriodStart() + ".." + (c.getPeriodEnd() != null ? c.getPeriodEnd() : "?")
                : "no-period";
        return "member " + member + " " + period;
    }

    // ── Wizard: preview / commit ──────────────────────────────────────────────

    /**
     * Pure read — selects the population the commit would bill, runs the
     * tenant pricing rules, and returns counts/totals. No persistence.
     */
    public Mono<BillingPreviewResponse> previewBilling(PreviewBillingRequest req) {
        return resolveCandidatesForTenant(req.groupIds(), req.memberIds(),
                        req.periodStart(), req.periodEnd(), req.insuranceLine())
                .flatMap(candidate -> applyPricing(candidate, req.periodStart(), req.periodEnd()))
                .collectList()
                .zipWith(billingCycleConfigRepository.findById(BillingCycleConfig.SINGLETON_ID)
                        .defaultIfEmpty(defaultCycleConfig()))
                .flatMap(tuple -> {
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
                                r.memberId(), r.dependantId(), r.memberNumber(),
                                r.personName(), r.personType(),
                                r.schemeId(), r.schemeName(),
                                r.groupId(), r.groupName(), r.ageBandName(),
                                r.amount(), r.currencyCode()));
                    }
                    int remainingMinutes = cooldownRemainingMinutes(cfg);
                    return Mono.deferContextual(ctx -> {
                        String tenantId = TenantContext.get(ctx);
                        return getMembershipModel(tenantId).map(model -> {
                            InvoiceProjection proj = projectInvoices(rows, model);
                            return new BillingPreviewResponse(
                                    rows.size(), totals, sample,
                                    remainingMinutes > 0,
                                    remainingMinutes > 0 ? remainingMinutes : null,
                                    proj.groupInvoices, proj.individualInvoices, model);
                        });
                    });
                });
    }

    /**
     * Persists contribution rows for the same selection. Honours
     * {@code billing_cycle_config.commit_cooldown_hours}; double-commits
     * inside that window throw {@link BillingCooldownException}.
     */
    @Transactional
    public Mono<BillingCommitResponse> commitBilling(CommitBillingRequest req, String actorId, String actorEmail) {
        return billingCycleConfigRepository.findById(BillingCycleConfig.SINGLETON_ID)
                .defaultIfEmpty(defaultCycleConfig())
                .flatMap(cfg -> {
                    int remaining = cooldownRemainingMinutes(cfg);
                    if (remaining > 0) {
                        return Mono.<BillingCommitResponse>error(new BillingCooldownException(remaining));
                    }
                    // Period guard — one commit per (period, line). The
                    // cooldown above only stops accidental double-clicks
                    // (hours); this stops a deliberate re-commit days
                    // later. The DB-level UNIQUE index added in V034
                    // makes this defense-in-depth — a race that beats
                    // the count check still hits the constraint.
                    return contributionRepository.countByPeriodAndLine(
                                    req.periodStart(), req.periodEnd(), req.insuranceLine())
                            .defaultIfEmpty(0L)
                            .flatMap(existing -> {
                                if (existing > 0) {
                                    return Mono.<BillingCommitResponse>error(
                                            new BillingPeriodAlreadyCommittedException(
                                                    req.periodStart(), req.periodEnd(),
                                                    req.insuranceLine(), existing));
                                }
                                return doCommit(req, actorId, actorEmail, cfg);
                            });
                });
    }

    private Mono<BillingCommitResponse> doCommit(CommitBillingRequest req, String actorId, String actorEmail,
                                                  BillingCycleConfig cfg) {
        UUID actorUuid = parseUuid(actorId);
        Instant now = Instant.now();

        return resolveCandidatesForTenant(req.groupIds(), req.memberIds(),
                        req.periodStart(), req.periodEnd(), req.insuranceLine())
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
                            "*",
                            req.periodStart().toString(),
                            req.periodEnd().toString(),
                            saved.size()).then();
                    Mono<Void> audit = Mono.deferContextual(ctx -> publishAudit(
                            TenantContext.get(ctx), "BillingCycle", "commit",
                            req.periodStart() + " to " + req.periodEnd(),
                            "CREATE", actorId, actorEmail,
                            null,
                            Map.of("rows", String.valueOf(saved.size()),
                                    "periodStart", req.periodStart().toString(),
                                    "periodEnd", req.periodEnd().toString())));

                    // Roll the per-member contributions up into Invoice rows
                    // according to the tenant's membership model. See
                    // generateInvoicesFor for the routing rules.
                    Mono<List<Invoice>> invoices = Mono.deferContextual(ctx -> {
                        String tenantId = TenantContext.get(ctx);
                        // Pass `now` down so every invoice from this commit
                        // shares the same committed_at — keeps snapshot
                        // windows aligned per the user's exact-instant rule.
                        return getMembershipModel(tenantId).flatMap(model ->
                                generateInvoicesFor(saved, model, actorUuid,
                                        req.periodStart(), req.periodEnd(), now));
                    });

                    return stamp.then(publish).then(audit).then(invoices)
                            .flatMap(generated -> Mono.deferContextual(ctx -> {
                                String tenantId = TenantContext.get(ctx);
                                return getMembershipModel(tenantId).map(model -> {
                                    long groupCount = generated.stream()
                                            .filter(i -> i.getGroupId() != null).count();
                                    long memberCount = generated.stream()
                                            .filter(i -> i.getGroupId() == null && i.getMemberId() != null).count();
                                    return new BillingCommitResponse((long) saved.size(), totals, now,
                                            groupCount, memberCount, model);
                                });
                            }));
                });
    }

    // ── Revoke ─────────────────────────────────────────────────────────────

    /**
     * Delete every contribution + invoice for the (period, line) so the
     * operator can re-commit a corrected run. Gated by a strict
     * "next month only" window — once the requested month becomes
     * the current month its contributions are active and changes
     * have to go through the corrections flow instead.
     *
     * <p>Bound to {@code billing:revoke_billing} on the frontend (the
     * gateway/RBAC layer doesn't enforce backend method-level permissions
     * today; the frontend hides the button for unauthorised users).
     */
    @Transactional
    public Mono<BillingRevokeResponse> revokeBilling(RevokeBillingRequest req, String actorId, String actorEmail) {
        LocalDate allowedStart = LocalDate.now().plusMonths(1).withDayOfMonth(1);
        if (req.periodStart() == null || !req.periodStart().equals(allowedStart)) {
            return Mono.error(new BillingNotRevocableException(req.periodStart(), allowedStart));
        }

        Instant now = Instant.now();
        String line = req.insuranceLine();

        // Two DELETEs in one transaction: invoices first (they FK back
        // to contribution rows via period in spirit if not in SQL),
        // then the contributions themselves. Per-line scoping joins
        // through schemes the same way the count query does.
        Mono<Long> deletedInvoices = db.sql("""
                DELETE FROM invoices
                 WHERE period_start = :start
                   AND period_end   = :end
                   AND ( :line IS NULL
                      OR EXISTS (SELECT 1 FROM schemes s
                                  WHERE s.id = invoices.scheme_id
                                    AND s.insurance_line = :line) )
                """)
                .bind("start", req.periodStart())
                .bind("end",   req.periodEnd())
                .bind("line",  line == null ? "" : line)
                .fetch().rowsUpdated();

        Mono<Long> deletedContributions = db.sql("""
                DELETE FROM contributions
                 WHERE period_start = :start
                   AND period_end   = :end
                   AND ( :line IS NULL
                      OR EXISTS (SELECT 1 FROM schemes s
                                  WHERE s.id = contributions.scheme_id
                                    AND s.insurance_line = :line) )
                """)
                .bind("start", req.periodStart())
                .bind("end",   req.periodEnd())
                .bind("line",  line == null ? "" : line)
                .fetch().rowsUpdated();

        return deletedInvoices.zipWith(deletedContributions)
                .flatMap(t -> Mono.deferContextual(ctx -> {
                    String tenantId = TenantContext.get(ctx);
                    long invoices = t.getT1();
                    long contributions = t.getT2();
                    log.info("[revoke] tenant={} period={} to {} line={} deleted invoices={} contributions={}",
                            tenantId, req.periodStart(), req.periodEnd(), line, invoices, contributions);
                    return publishAudit(tenantId, "BillingCycle", "revoke",
                                    req.periodStart() + " to " + req.periodEnd(),
                                    "DELETE", actorId, actorEmail,
                                    Map.of("contributions", String.valueOf(contributions),
                                            "invoices", String.valueOf(invoices),
                                            "periodStart", req.periodStart().toString(),
                                            "periodEnd", req.periodEnd().toString(),
                                            "insuranceLine", line == null ? "(all)" : line),
                                    null)
                            .thenReturn(new BillingRevokeResponse(
                                    contributions, invoices,
                                    req.periodStart(), req.periodEnd(), line, now));
                }));
    }

    // ── Membership-model-aware invoice generation ──────────────────────────
    // After per-member contributions are persisted, roll them up into
    // Invoice rows. The routing depends on the tenant's membershipModel:
    //
    //   INDIVIDUAL_ONLY  → 1 invoice per (member, currency). Group_id ignored.
    //   GROUP_ONLY       → 1 invoice per (group, currency). Members without a
    //                      group fall back to per-member invoices (defensive —
    //                      this is a data anomaly).
    //   BOTH             → grouped members covered by a (group, currency)
    //                      invoice; ungrouped members get a (member, currency)
    //                      invoice each.
    //
    // Each generated invoice is consolidated across schemes (per the
    // operator-chosen aggregation level — "One consolidated invoice per
    // (group, period)") with the caveat that we still split by currency
    // because the invoices table carries a single currency_code column.

    private Mono<List<Invoice>> generateInvoicesFor(List<Contribution> contributions, String model,
                                                     UUID actorUuid, LocalDate periodStart, LocalDate periodEnd,
                                                     Instant committedAt) {
        if (contributions.isEmpty()) return Mono.just(List.of());

        Map<RoutingKey, List<Contribution>> buckets = new LinkedHashMap<>();
        for (Contribution c : contributions) {
            RoutingKey key = computeRoutingKey(c, model);
            buckets.computeIfAbsent(key, k -> new ArrayList<>()).add(c);
        }

        // concatMap (not flatMap) keeps the snapshot prior-lookup ordered
        // — if two invoices in this commit share the same (holder, currency),
        // the second one must read the first as its prior. Sequential
        // persistence guarantees that ordering.
        return Flux.fromIterable(buckets.entrySet())
                .concatMap(e -> persistInvoiceFor(e.getKey(), e.getValue(), actorUuid,
                        periodStart, periodEnd, committedAt))
                .collectList();
    }

    private Mono<Invoice> persistInvoiceFor(RoutingKey key, List<Contribution> rows, UUID actorUuid,
                                            LocalDate periodStart, LocalDate periodEnd,
                                            Instant committedAt) {
        BigDecimal total = rows.stream()
                .map(c -> c.getAmount() != null ? c.getAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return generateInvoiceNumber().flatMap(number -> {
            Invoice inv = new Invoice();
            // @Id left null — DB default (DEFAULT gen_random_uuid()).
            inv.setInvoiceNumber(number);
            inv.setGroupId(key.groupId());
            inv.setMemberId(key.memberId());
            inv.setSchemeId(null); // consolidated across schemes
            inv.setTotalAmount(total);
            inv.setCurrencyCode(key.currencyCode());
            inv.setStatus("issued");
            inv.setPeriodStart(periodStart);
            inv.setPeriodEnd(periodEnd);
            inv.setIssuedAt(committedAt);
            inv.setDueDate(periodEnd.plusDays(30));
            inv.setCreatedAt(committedAt);
            inv.setUpdatedAt(committedAt);
            inv.setCreatedBy(actorUuid);
            // Capture the financial snapshot BEFORE save so the next
            // invoice in this commit (same holder+currency) reads
            // this one as its prior. See InvoiceSnapshotService for
            // the half-open [prior, this) window rule.
            return invoiceSnapshotService.stampSnapshot(inv, committedAt)
                    .flatMap(invoiceRepository::save);
        }).flatMap(saved -> {
            // Back-link every contribution to its invoice so audit + payment
            // allocation can trace the rollup later without a heuristic join.
            return Flux.fromIterable(rows)
                    .concatMap(c -> {
                        c.setInvoiceId(saved.getId());
                        return contributionRepository.save(c);
                    })
                    .then(Mono.just(saved));
        }).flatMap(saved -> Mono.deferContextual(ctx ->
            // Resolve the friendly group/member name now — the PDF
            // renderer in file-service uses it on the document header
            // (plan §1A). Single round-trip; defaults to null if the
            // holder row is missing so the renderer falls back to its
            // truncated-UUID label.
            resolveRecipientName(saved.getGroupId(), saved.getMemberId())
                .defaultIfEmpty("")
                // Fan out to file-service (PDF rendering) and onward to
                // notification-service (email delivery). Fire-and-forget so the
                // commit response stays snappy — failures are visible in
                // audit + NotificationSent events, not blocking on Kafka latency.
                .flatMap(recipientName -> eventPublisher.publishInvoiceIssued(
                        new ContributionEventPublisher.InvoiceIssuedPayload(
                                saved.getId().toString(),
                                saved.getInvoiceNumber(),
                                TenantContext.get(ctx),
                                saved.getGroupId()  != null ? saved.getGroupId().toString()  : null,
                                saved.getMemberId() != null ? saved.getMemberId().toString() : null,
                                saved.getCurrencyCode(),
                                saved.getTotalAmount().toPlainString(),
                                saved.getPeriodStart().toString(),
                                saved.getPeriodEnd().toString(),
                                saved.getDueDate().toString(),
                                saved.getCommittedAt() != null ? saved.getCommittedAt().toString() : null,
                                saved.getOpeningBalance()      != null ? saved.getOpeningBalance().toPlainString()      : null,
                                saved.getClosingBalance()      != null ? saved.getClosingBalance().toPlainString()      : null,
                                saved.getPaymentsInWindow()    != null ? saved.getPaymentsInWindow().toPlainString()    : null,
                                saved.getAdjustmentsInWindow() != null ? saved.getAdjustmentsInWindow().toPlainString() : null,
                                recipientName.isBlank() ? null : recipientName)))
                .thenReturn(saved)));
    }

    /**
     * Look up the friendly group/member name from the tenant schema
     * before publishing INVOICE_ISSUED. Used to populate the PDF
     * header so the rendered document shows the real name instead of
     * the truncated-UUID fallback in file-service.
     */
    private Mono<String> resolveRecipientName(UUID groupId, UUID memberId) {
        if (groupId != null) {
            return db.sql("SELECT name FROM groups WHERE id = :id")
                    .bind("id", groupId)
                    .map(row -> row.get("name", String.class))
                    .one()
                    .onErrorReturn("");
        }
        if (memberId != null) {
            return db.sql("SELECT (first_name || ' ' || last_name) AS name FROM members WHERE id = :id")
                    .bind("id", memberId)
                    .map(row -> row.get("name", String.class))
                    .one()
                    .onErrorReturn("");
        }
        return Mono.just("");
    }

    private RoutingKey computeRoutingKey(Contribution c, String model) {
        UUID groupId = c.getGroupId();
        UUID memberId = c.getMemberId();
        String currency = c.getCurrencyCode() != null ? c.getCurrencyCode() : "USD";
        return switch (model == null ? "BOTH" : model) {
            case "INDIVIDUAL_ONLY" -> new RoutingKey(null, memberId, currency);
            case "GROUP_ONLY" -> groupId != null
                    ? new RoutingKey(groupId, null, currency)
                    : new RoutingKey(null, memberId, currency); // anomaly fallback
            default /* BOTH */ -> groupId != null
                    ? new RoutingKey(groupId, null, currency)
                    : new RoutingKey(null, memberId, currency);
        };
    }

    private InvoiceProjection projectInvoices(List<PricedCandidate> rows, String model) {
        java.util.Set<RoutingKey> groupKeys = new java.util.HashSet<>();
        java.util.Set<RoutingKey> memberKeys = new java.util.HashSet<>();
        for (PricedCandidate r : rows) {
            String currency = r.currencyCode() != null ? r.currencyCode() : "USD";
            switch (model == null ? "BOTH" : model) {
                case "INDIVIDUAL_ONLY" -> memberKeys.add(new RoutingKey(null, r.memberId(), currency));
                case "GROUP_ONLY" -> {
                    if (r.groupId() != null) groupKeys.add(new RoutingKey(r.groupId(), null, currency));
                    else memberKeys.add(new RoutingKey(null, r.memberId(), currency));
                }
                default -> {
                    if (r.groupId() != null) groupKeys.add(new RoutingKey(r.groupId(), null, currency));
                    else memberKeys.add(new RoutingKey(null, r.memberId(), currency));
                }
            }
        }
        return new InvoiceProjection(groupKeys.size(), memberKeys.size());
    }

    /**
     * Read the tenant's pricing_model from public.tenants. Returns
     * STANDARD for missing tenants or rows where the column is null —
     * that's the safest fallback (matches every tenant's behaviour
     * pre-V118 when the column didn't exist yet). STANDARD means
     * "use whatever the scheme's insurance line normally prices off"
     * (age_groups for HEALTH, sum-assured bands for LIFE, etc.);
     * INDIVIDUAL means "honour per-member billing_override_amount
     * when set, scheme-default as fallback".
     */
    private Mono<String> getPricingModel(String tenantId) {
        if (tenantId == null) return Mono.just("STANDARD");
        UUID tid;
        try { tid = UUID.fromString(tenantId); }
        catch (IllegalArgumentException e) { return Mono.just("STANDARD"); }
        return db.sql("SELECT pricing_model FROM public.tenants WHERE id = :tenantId")
                .bind("tenantId", tid)
                .map(row -> row.get("pricing_model", String.class))
                .one()
                .defaultIfEmpty("STANDARD")
                .onErrorReturn("STANDARD");
    }

    private Mono<String> getMembershipModel(String tenantId) {
        if (tenantId == null) return Mono.just("BOTH");
        UUID tid;
        try { tid = UUID.fromString(tenantId); }
        catch (IllegalArgumentException e) { return Mono.just("BOTH"); }
        return db.sql("SELECT membership_model FROM public.tenants WHERE id = :tenantId")
                .bind("tenantId", tid)
                .map(row -> row.get("membership_model", String.class))
                .one()
                .defaultIfEmpty("BOTH")
                .onErrorReturn("BOTH");
    }

    private record RoutingKey(UUID groupId, UUID memberId, String currencyCode) {}
    private record InvoiceProjection(long groupInvoices, long individualInvoices) {}

    private Mono<Contribution> persistContribution(PricedCandidate priced, CommitBillingRequest req,
                                                   UUID actorUuid, Instant now) {
        Contribution c = new Contribution();
        c.setMemberId(priced.memberId());
        c.setDependantId(priced.dependantId()); // null for member's own line
        c.setSchemeId(priced.schemeId());
        c.setGroupId(priced.groupId());
        c.setAmount(priced.amount());
        c.setCurrencyCode(priced.currencyCode());
        c.setAgeGroupId(priced.ageGroupId());   // frozen for historical replay
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
     * Per-tenant entry point: reads the tenant's pricing_model + the
     * insurance line for this run, then delegates to the matching
     * {@link com.medfund.contributions.service.candidate.CandidateResolver}.
     *
     * <p>Defaults to HEALTH when no line is supplied — keeps the v1
     * single-line wizard working without an explicit line on every
     * enqueue. Multi-line tenants pass the line via the wizard's tab
     * (Part 4.5) and the dispatcher routes to the right resolver.
     */
    private Flux<PersonCandidate> resolveCandidatesForTenant(List<UUID> groupIds, List<UUID> memberIds,
                                                              LocalDate periodStart, LocalDate periodEnd) {
        return resolveCandidatesForTenant(groupIds, memberIds, periodStart, periodEnd, "HEALTH");
    }

    private Flux<PersonCandidate> resolveCandidatesForTenant(List<UUID> groupIds, List<UUID> memberIds,
                                                              LocalDate periodStart, LocalDate periodEnd,
                                                              String insuranceLine) {
        com.medfund.contributions.service.candidate.CandidateResolver resolver =
                candidateResolvers.get(insuranceLine != null ? insuranceLine : "HEALTH");
        if (resolver == null) {
            log.warn("No CandidateResolver registered for line {} — skipping run", insuranceLine);
            return Flux.empty();
        }
        return Mono.deferContextual(ctx -> getPricingModel(TenantContext.get(ctx)))
                .flatMapMany(mode -> resolver.resolveCandidates(groupIds, memberIds, periodStart, periodEnd, mode));
    }


    /**
     * Build a transient {@link Contribution} for the person, pre-set the
     * amount from the age-band lookup, then run the tenant pricing rules
     * so they get a final say (loaded premiums, custom multipliers, …).
     * The transient row is never saved here; preview/commit decide that.
     */
    private Mono<PricedCandidate> applyPricing(PersonCandidate p, LocalDate periodStart, LocalDate periodEnd) {
        Contribution transient_ = new Contribution();
        transient_.setMemberId(p.memberId());
        transient_.setDependantId(p.dependantId());
        transient_.setSchemeId(p.schemeId());
        transient_.setGroupId(p.groupId());
        transient_.setAgeGroupId(p.effectiveAgeGroupId());
        transient_.setAmount(p.priceAmount() != null ? p.priceAmount() : BigDecimal.ZERO);
        // Currency precedence: age-group's currency (closest to the price) →
        // scheme currency (fallback when the band has no explicit currency) →
        // USD (last-resort fallback).
        String currency = p.priceCurrency() != null ? p.priceCurrency()
                         : p.schemeCurrency() != null ? p.schemeCurrency()
                         : "USD";
        transient_.setCurrencyCode(currency);
        transient_.setPeriodStart(periodStart);
        transient_.setPeriodEnd(periodEnd);
        transient_.setStatus("pending");

        return pricingService.price(transient_)
                .then(applyAiMultiplierIfEnabled(transient_, p))
                .thenReturn(new PricedCandidate(
                        p.memberId(), p.dependantId(), p.memberNumber(), p.personName(), p.personType(),
                        p.schemeId(), p.schemeName(), p.groupId(), p.groupName(),
                        p.effectiveAgeGroupId(), p.ageBandName(),
                        transient_.getAmount() != null ? transient_.getAmount() : BigDecimal.ZERO,
                        transient_.getCurrencyCode()));
    }

    /**
     * Phase C: when the tenant's pricing_model is AI_DRIVEN, call the
     * ai-service /api/v1/pricing/score endpoint with the per-member
     * signals (chronic conditions, smoking, BMI, …) and multiply the
     * resolved amount by the returned multiplier. INDIVIDUAL overrides
     * still win — a hand-curated billing_override_amount short-circuits
     * the AI path because the resolved amount IS the override (see the
     * COALESCE in resolveCandidates), and we never multiply an override
     * value through the scorer.
     *
     * <p>Fails open: a network error or non-2xx response logs and
     * leaves the amount unchanged. The billing run should never
     * collapse on an unreachable AI service.
     */
    private Mono<Void> applyAiMultiplierIfEnabled(Contribution c, PersonCandidate p) {
        if (c.getMemberId() == null) return Mono.empty();
        return Mono.deferContextual(ctx -> getPricingModel(TenantContext.get(ctx)))
                .flatMap(mode -> {
                    if (!"AI_DRIVEN".equals(mode)) return Mono.empty();
                    return aiPricingClient.score(c)
                            .doOnNext(multiplier -> {
                                if (multiplier != null && c.getAmount() != null) {
                                    BigDecimal scaled = c.getAmount()
                                            .multiply(BigDecimal.valueOf(multiplier))
                                            .setScale(4, java.math.RoundingMode.HALF_UP);
                                    log.info("[ai-pricing] member={} base={} multiplier={} scaled={}",
                                            c.getMemberId(), c.getAmount(), multiplier, scaled);
                                    c.setAmount(scaled);
                                }
                            })
                            .onErrorResume(err -> {
                                log.warn("[ai-pricing] member={} skipped — scorer error: {}",
                                        c.getMemberId(), err.getMessage());
                                return Mono.empty();
                            })
                            .then();
                });
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

    // PersonCandidate moved to service/candidate/PersonCandidate.java as
    // a public record so per-line CandidateResolver implementations can
    // construct it. The aliased import at the top of this file keeps the
    // existing call-sites readable.

    private record PricedCandidate(
            UUID memberId, UUID dependantId, String memberNumber, String personName, String personType,
            UUID schemeId, String schemeName, UUID groupId, String groupName,
            UUID ageGroupId, String ageBandName,
            BigDecimal amount, String currencyCode) {}

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

    /**
     * Scheduled auto-billing entry point. Invoked by
     * {@link com.medfund.contributions.job.BillingCycleExecutor} under the
     * tenant's reactor context (set by the dispatcher from the config row's
     * tenant_id). Bills every active member in the tenant for the current
     * month, with the SYSTEM actor recorded on the audit trail.
     *
     * <p>Cooldown still applies — if {@link #commitBilling} throws
     * {@link BillingCooldownException} the scheduled run is marked FAILED
     * with the cooldown message, which is exactly the behaviour we want
     * (prevents the cron from doubling-up on a manual commit).
     */
    public Mono<Void> runAutoBilling() {
        LocalDate today = LocalDate.now();
        LocalDate periodStart = today.withDayOfMonth(1);
        LocalDate periodEnd = today.withDayOfMonth(today.lengthOfMonth());
        log.info("Auto billing for period {} to {}", periodStart, periodEnd);
        // Auto-billing cron runs HEALTH by default — multi-line tenants
        // schedule their own per-line cron entries by setting insuranceLine
        // in the job's settings JSON.
        CommitBillingRequest req = new CommitBillingRequest(periodStart, periodEnd, null, null, null);
        return commitBilling(req, AuditActor.SYSTEM_ID, AuditActor.SYSTEM_EMAIL)
            .doOnNext(resp -> log.info("Auto billing committed {} contributions across {} group + {} member invoices",
                resp.contributionsCreated(), resp.groupInvoicesCreated(), resp.individualInvoicesCreated()))
            .then();
    }
}
