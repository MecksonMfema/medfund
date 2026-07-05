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
import java.math.RoundingMode;
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
                    .then(Mono.defer(() -> contributionRepository.save(contribution)))
                    // Same pairing as persistContribution — every contribution
                    // write must land on the running balance or the customer's
                    // outstanding drifts.
                    .flatMap(saved -> balanceService.applyContributionDebit(saved).thenReturn(saved));
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
                // Due by the end of the billing month itself — contributions
                // for month X are payable by the last day of month X.
                invoice.setDueDate(periodEnd);
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
                            moneyDisplay(saved.getTotalAmount()),
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
        // Contribution statement number. The historical INV- prefix was
        // dropped in the 2026-07 rename that reframed these documents
        // from "invoices" to "contribution statements" — the CS- prefix
        // makes the document type unambiguous on any exported PDF or
        // reconciliation report. Existing rows with INV- prefixes remain
        // valid; the uniqueness check spans both eras.
        String number = "CS-" + String.format("%06d", ThreadLocalRandom.current().nextInt(0, 999999));
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

    // ── Charge preview (per-subject live projection) ──────────────────────────

    /**
     * Live projection of what a single subject (one group or one
     * individual) would owe in the next billing cycle. Reuses the same
     * candidate resolver + pricing rules the real commit uses, so the
     * projected amount matches what a run right now would post — but
     * writes nothing.
     *
     * <p>Two subject-level filters layered on top of the resolver's
     * standard status guard:
     * <ul>
     *   <li>Terminating members whose {@code termination_date} lands
     *       before {@code periodStart} — they're on their last
     *       serviced cycle and shouldn't be charged for the next
     *       one.</li>
     *   <li>Optional currency filter — a multi-currency tenant can
     *       narrow the projection to one currency at a time. Omit to
     *       return every currency.</li>
     * </ul>
     *
     * <p>Future scheme upgrades/downgrades ({@code billing_age_group_id}
     * + {@code billing_override_effective_from} <= periodEnd) and
     * per-member custom pricing ({@code billing_override_amount}) are
     * already honoured by the candidate resolver — no extra logic
     * needed here.
     *
     * @param subjectType {@code "MEMBER"} or {@code "GROUP"}
     * @param subjectId   the group id or member id
     * @param currency    optional; null = every currency
     */
    public Mono<com.medfund.contributions.dto.ChargePreviewResponse> chargePreview(
            String subjectType, UUID subjectId, String currency) {
        java.time.LocalDate[] window = computeProjectedPeriod(LocalDate.now());
        LocalDate periodStart = window[0];
        LocalDate periodEnd   = window[1];

        List<UUID> groupIds  = "GROUP".equalsIgnoreCase(subjectType)  ? List.of(subjectId) : null;
        List<UUID> memberIds = "MEMBER".equalsIgnoreCase(subjectType) ? List.of(subjectId) : null;

        // Resolve the subject's display name in parallel with the
        // candidate pull. The client renders this in the result header
        // so the operator confirms they're looking at the right subject.
        Mono<String> subjectNameMono = "GROUP".equalsIgnoreCase(subjectType)
                ? db.sql("SELECT name FROM groups WHERE id = :id")
                        .bind("id", subjectId)
                        .map(row -> row.get("name", String.class))
                        .one().defaultIfEmpty("")
                : db.sql("SELECT (first_name || ' ' || last_name) AS name FROM members WHERE id = :id")
                        .bind("id", subjectId)
                        .map(row -> row.get("name", String.class))
                        .one().defaultIfEmpty("");

        // Terminating-member id set for the selection. One query,
        // keyed by the subject; a distinct SELECT rather than a bind
        // to the resolver so the shared multi-line resolver interface
        // stays untouched.
        String terminatingSql = "GROUP".equalsIgnoreCase(subjectType)
                ? """
                        SELECT id FROM members
                         WHERE group_id = :sid
                           AND termination_date IS NOT NULL
                           AND termination_date < :cutoff
                        """
                : """
                        SELECT id FROM members
                         WHERE id = :sid
                           AND termination_date IS NOT NULL
                           AND termination_date < :cutoff
                        """;
        Mono<java.util.Set<UUID>> terminatingIdsMono = db.sql(terminatingSql)
                .bind("sid", subjectId)
                .bind("cutoff", periodStart)
                .map(row -> row.get("id", UUID.class))
                .all()
                .collect(java.util.stream.Collectors.toSet());

        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            Mono<String> pricingModelMono = getPricingModel(tenantId);

            return Mono.zip(subjectNameMono, terminatingIdsMono, pricingModelMono)
                    .flatMap(tuple -> {
                        String subjectName = tuple.getT1();
                        java.util.Set<UUID> terminatingIds = tuple.getT2();
                        String pricingModel = tuple.getT3();

                        return resolveCandidatesForTenant(groupIds, memberIds, periodStart, periodEnd)
                                .filter(pc -> !terminatingIds.contains(pc.memberId()))
                                .flatMap(pc -> applyPricing(pc, periodStart, periodEnd))
                                // Currency filter is applied post-pricing
                                // because the price row's currency drives
                                // the final call (age-group price currency
                                // > scheme currency > USD). A pre-filter
                                // on scheme currency would over-filter in
                                // tenants where the age-band overrides it.
                                .filter(pc -> currency == null || currency.isBlank()
                                        || currency.equalsIgnoreCase(pc.currencyCode()))
                                .collectList()
                                .map(BillingService::sortByHousehold)
                                .flatMap(pricedList -> decorateWithOverrides(
                                        pricedList, pricingModel, periodStart, periodEnd)
                                        .map(lines -> buildResponse(
                                                subjectType, subjectId, subjectName,
                                                periodStart, periodEnd,
                                                lines, terminatingIds.size())));
                    });
        });
    }

    /**
     * Second-pass decoration: for every priced candidate, look up the
     * per-member (or per-dependant) override fields and surface two
     * flags on the response line —
     * <ul>
     *   <li>{@code isCustomPriced} — {@code billing_override_amount}
     *       drove the amount for this cycle. Only true when the
     *       tenant's pricing_model is {@code INDIVIDUAL} (the STANDARD
     *       model's resolver ignores the override) and the override
     *       has an effective_from that has been reached by
     *       periodEnd.</li>
     *   <li>{@code scheduledSchemeChangeFrom} — a future
     *       {@code billing_age_group_id} is set but its effective_from
     *       is still ahead of periodStart, so the age-band change
     *       hasn't landed yet. The operator sees "change effective
     *       [date]" and can plan.</li>
     * </ul>
     *
     * <p>One SELECT per source table (members + dependants), keyed by
     * the resolved id list from the candidate flux, so the total cost
     * is two indexed lookups regardless of subject size.
     */
    private Mono<List<com.medfund.contributions.dto.ChargePreviewLine>> decorateWithOverrides(
            List<PricedCandidate> priced, String pricingModel,
            LocalDate periodStart, LocalDate periodEnd) {
        if (priced.isEmpty()) {
            return Mono.just(List.of());
        }

        List<UUID> memberIds = priced.stream()
                .filter(p -> "MEMBER".equalsIgnoreCase(p.personType()))
                .map(PricedCandidate::memberId)
                .distinct()
                .toList();
        List<UUID> dependantIds = priced.stream()
                .filter(p -> "DEPENDANT".equalsIgnoreCase(p.personType()))
                .map(PricedCandidate::dependantId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();

        Mono<java.util.Map<UUID, OverrideMeta>> memberMetaMono = memberIds.isEmpty()
                ? Mono.just(java.util.Map.of())
                : db.sql("""
                        SELECT id AS pid,
                               billing_override_amount AS override_amount,
                               billing_override_effective_from AS effective_from,
                               billing_age_group_id AS override_age_group
                          FROM members
                         WHERE id = ANY(:ids)
                        """)
                        .bind("ids", memberIds.toArray(UUID[]::new))
                        .map(row -> new java.util.AbstractMap.SimpleEntry<>(
                                row.get("pid", UUID.class),
                                new OverrideMeta(
                                        row.get("override_amount", java.math.BigDecimal.class),
                                        row.get("effective_from", LocalDate.class),
                                        row.get("override_age_group", UUID.class))))
                        .all()
                        .collectMap(java.util.Map.Entry::getKey, java.util.Map.Entry::getValue);

        Mono<java.util.Map<UUID, OverrideMeta>> dependantMetaMono = dependantIds.isEmpty()
                ? Mono.just(java.util.Map.of())
                : db.sql("""
                        SELECT id AS pid,
                               billing_override_amount AS override_amount,
                               billing_override_effective_from AS effective_from,
                               billing_age_group_id AS override_age_group
                          FROM dependants
                         WHERE id = ANY(:ids)
                        """)
                        .bind("ids", dependantIds.toArray(UUID[]::new))
                        .map(row -> new java.util.AbstractMap.SimpleEntry<>(
                                row.get("pid", UUID.class),
                                new OverrideMeta(
                                        row.get("override_amount", java.math.BigDecimal.class),
                                        row.get("effective_from", LocalDate.class),
                                        row.get("override_age_group", UUID.class))))
                        .all()
                        .collectMap(java.util.Map.Entry::getKey, java.util.Map.Entry::getValue);

        boolean individualModel = "INDIVIDUAL".equalsIgnoreCase(pricingModel);

        return Mono.zip(memberMetaMono, dependantMetaMono)
                .map(tuple -> composeDecoratedLines(
                        priced, tuple.getT1(), tuple.getT2(),
                        individualModel, periodStart, periodEnd));
    }

    /**
     * Group priced candidates into household clusters — every member's
     * row is immediately followed by their dependants. Without this the
     * resolver's {@code UNION ALL} returns MEMBER + DEPENDANT rows in an
     * undefined Postgres order, so a group with 20 members + 40
     * dependants renders as a scrambled table where a dependant can sit
     * three rows away from their principal.
     *
     * <p>Sort key: {@code (memberId, personTypeRank)} where MEMBER
     * ranks before DEPENDANT. memberId is chosen over memberNumber
     * because it's guaranteed non-null on both MEMBER and DEPENDANT
     * rows (the resolver copies the principal's member_id onto every
     * dependant row for exactly this cluster-together purpose).
     * Comparator stays a stable sort under Collections.sort so any
     * within-household ordering the resolver already produced (e.g.
     * dependant creation order) is preserved.
     *
     * <p>Kept static + package-private so the same-package IT can
     * assert the ordering invariant without booting the Spring context.
     */
    static List<PricedCandidate> sortByHousehold(List<PricedCandidate> priced) {
        List<PricedCandidate> sorted = new java.util.ArrayList<>(priced);
        sorted.sort((a, b) -> {
            int byMember = compareUuidsNullsLast(a.memberId(), b.memberId());
            if (byMember != 0) return byMember;
            // Within a household: MEMBER row first, DEPENDANT rows after.
            // The resolver sets personType to the string literals
            // 'MEMBER' or 'DEPENDANT' so a fixed rank map is precise
            // and stable.
            return Integer.compare(personTypeRank(a.personType()),
                    personTypeRank(b.personType()));
        });
        return sorted;
    }

    private static int personTypeRank(String type) {
        return "MEMBER".equalsIgnoreCase(type) ? 0 : 1;
    }

    private static int compareUuidsNullsLast(UUID a, UUID b) {
        if (a == null && b == null) return 0;
        if (a == null) return 1;
        if (b == null) return -1;
        return a.compareTo(b);
    }

    /**
     * Compute the projected billing cycle window for a charge preview.
     * Returns {@code [periodStart, periodEnd]} where {@code periodStart}
     * is the 1st of next month and {@code periodEnd} is the last day of
     * that same month.
     *
     * <p>Extracted from {@link #chargePreview} so a targeted unit test
     * locks the semantic — a well-meaning "fix" that flipped this to
     * the *current* month would silently redefine the whole feature
     * (operators would suddenly see the current cycle's already-billed
     * numbers instead of the projection). The helper is package-private
     * + static so the same-package test can call it without booting the
     * Spring context.
     *
     * @param today anchor date (usually {@code LocalDate.now()}); passed
     *              in so the test can drive edge cases like end-of-year
     *              (Dec → Jan) and leap-year February deterministically.
     */
    static java.time.LocalDate[] computeProjectedPeriod(LocalDate today) {
        LocalDate periodStart = today.plusMonths(1).withDayOfMonth(1);
        LocalDate periodEnd   = periodStart.plusMonths(1).minusDays(1);
        return new LocalDate[] { periodStart, periodEnd };
    }

    /**
     * Pure decoration step — kept package-private + static so it can be
     * unit-tested without a DB. Given priced candidates + already-
     * fetched override metadata + pricing-model flag + projected
     * period bounds, produces the final response lines with
     * {@code isCustomPriced} and {@code scheduledSchemeChangeFrom}
     * set. The two-flag matrix here is the whole "custom pricing"
     * surface an operator sees on the breakdown table, so a test on
     * this method locks the behaviour without paying for a full
     * Testcontainers schema fixture.
     */
    static List<com.medfund.contributions.dto.ChargePreviewLine> composeDecoratedLines(
            List<PricedCandidate> priced,
            java.util.Map<UUID, OverrideMeta> byMember,
            java.util.Map<UUID, OverrideMeta> byDependant,
            boolean individualModel,
            LocalDate periodStart, LocalDate periodEnd) {
        List<com.medfund.contributions.dto.ChargePreviewLine> lines = new java.util.ArrayList<>(priced.size());
        for (PricedCandidate p : priced) {
            OverrideMeta meta = "DEPENDANT".equalsIgnoreCase(p.personType())
                    ? byDependant.get(p.dependantId())
                    : byMember.get(p.memberId());
            boolean custom = false;
            LocalDate scheduledFrom = null;
            if (meta != null) {
                // Custom pricing: only marked true when the resolver
                // would have actually picked the override (INDIVIDUAL
                // model + amount set + effective_from reached by
                // periodEnd). Matches the resolver's COALESCE CASE
                // exactly so the flag never lies.
                custom = individualModel
                        && meta.overrideAmount() != null
                        && (meta.effectiveFrom() == null
                                || !meta.effectiveFrom().isAfter(periodEnd));

                // Scheduled scheme change: a future
                // billing_age_group_id whose effective_from hasn't
                // been reached yet. Surfaces the "priced at old rate
                // this cycle, moving to new rate on [date]" narrative.
                if (meta.overrideAgeGroupId() != null
                        && meta.effectiveFrom() != null
                        && meta.effectiveFrom().isAfter(periodStart)) {
                    scheduledFrom = meta.effectiveFrom();
                }
            }
            lines.add(new com.medfund.contributions.dto.ChargePreviewLine(
                    p.memberId(), p.dependantId(), p.memberNumber(),
                    p.personName(), p.personType(),
                    p.schemeId(), p.schemeName(),
                    p.groupId(), p.groupName(),
                    p.ageGroupId(), p.ageBandName(),
                    p.amount(), p.currencyCode(),
                    custom, scheduledFrom));
        }
        return lines;
    }

    /** Compose the response envelope from the decorated line list. */
    private com.medfund.contributions.dto.ChargePreviewResponse buildResponse(
            String subjectType, UUID subjectId, String subjectName,
            LocalDate periodStart, LocalDate periodEnd,
            List<com.medfund.contributions.dto.ChargePreviewLine> lines,
            int excludedTerminating) {
        java.util.Map<String, java.math.BigDecimal> totals = new java.util.LinkedHashMap<>();
        for (com.medfund.contributions.dto.ChargePreviewLine l : lines) {
            totals.merge(l.currencyCode(), l.amount(), java.math.BigDecimal::add);
        }
        return new com.medfund.contributions.dto.ChargePreviewResponse(
                subjectType.toUpperCase(),
                subjectId,
                subjectName,
                periodStart, periodEnd,
                lines,
                totals,
                excludedTerminating,
                java.time.Instant.now());
    }

    /**
     * Per-subject override metadata used by
     * {@link #decorateWithOverrides}. Package-visible so the pure
     * {@link #composeDecoratedLines} helper can be tested from the
     * same package without exposing the DB fetch.
     */
    record OverrideMeta(
            java.math.BigDecimal overrideAmount,
            LocalDate effectiveFrom,
            UUID overrideAgeGroupId) {}

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
        // NULLIF-in-SQL wants a bindable value. Coerce null → "" here
        // so downstream .bind("line", …) always has a non-null; the
        // SQL uses NULLIF(:line, '') IS NULL to detect the no-filter case.
        String lineBind = line == null ? "" : line;

        // 1) Reverse the running-balance debit for every contribution
        //    we're about to delete. Streams the contributions one at a
        //    time and pairs each with the same upsertMember/upsertGroup
        //    path that applyContributionDebit uses, just with the
        //    amount negated and reason="CONTRIBUTION_REVOKED". Must run
        //    BEFORE the DELETE so the amount column is still readable.
        Mono<Void> reverseBalances = contributionRepository
                .findByPeriodAndLine(req.periodStart(), req.periodEnd(), line)
                .concatMap(balanceService::reverseContributionDebit)
                .then();

        // 2) Enumerate the invoice IDs in scope BEFORE any DELETE runs.
        //    Group invoices have scheme_id = NULL — a straight
        //    invoices.scheme_id join misses them — so we resolve line
        //    scope via the invoice's contributions, which always carry
        //    scheme_id. Individual invoices fall through the same
        //    predicate because their contribution's scheme_id matches
        //    their own scheme_id.
        //
        //    .cache() is load-bearing: this Mono is subscribed twice —
        //    once by capturePdfPointers (before the contribution DELETE)
        //    and once by deletedInvoices (after). Without caching, the
        //    second subscribe re-runs the SELECT after contributions are
        //    gone, and group invoices — which only match via the
        //    contributions-join branch when a line filter is set — drop
        //    out of the ID list. The invoice then survives the DELETE
        //    even though its contributions and balance were already
        //    reversed. Caching pins the enumeration to a single query.
        Mono<List<UUID>> targetInvoiceIds = db.sql("""
                SELECT DISTINCT i.id
                  FROM invoices i
                 WHERE i.period_start = :start
                   AND i.period_end   = :end
                   AND ( NULLIF(:line, '') IS NULL
                      OR EXISTS (SELECT 1 FROM contributions c
                                   JOIN schemes s ON s.id = c.scheme_id
                                  WHERE c.invoice_id = i.id
                                    AND s.insurance_line = :line)
                      OR EXISTS (SELECT 1 FROM schemes s
                                  WHERE s.id = i.scheme_id
                                    AND s.insurance_line = :line) )
                """)
                .bind("start", req.periodStart())
                .bind("end",   req.periodEnd())
                .bind("line",  lineBind)
                .map(row -> row.get("id", UUID.class))
                .all()
                .collectList()
                .cache();

        // 3) Capture (bucket, object_key) pointers for every invoice
        //    we're about to remove so we can publish per-blob delete
        //    events after the DELETE. invoice_pdfs is ON DELETE
        //    CASCADE on invoices(id), so the pointer row goes away;
        //    the MinIO blob doesn't unless file-service is told.
        Mono<List<PdfPointer>> capturePdfPointers = targetInvoiceIds.flatMap(ids -> {
            if (ids.isEmpty()) return Mono.just(List.<PdfPointer>of());
            return db.sql("""
                    SELECT ip.invoice_id, ip.bucket, ip.object_key
                      FROM invoice_pdfs ip
                     WHERE ip.invoice_id IN (:ids)
                    """)
                    .bind("ids", ids)
                    .map(row -> new PdfPointer(
                            row.get("invoice_id", UUID.class),
                            row.get("bucket", String.class),
                            row.get("object_key", String.class)))
                    .all()
                    .collectList();
        });

        // 4) DELETE contributions first — the FK on contributions.invoice_id
        //    would otherwise block the invoice DELETE.
        //
        //    Scope covers every contribution matching the period + line
        //    filter (period+scheme.insurance_line), not just those with
        //    invoice_id set — a paranoid orphan sweep for pre-V020 rows.
        Mono<Long> deletedContributions = db.sql("""
                DELETE FROM contributions
                 WHERE period_start = :start
                   AND period_end   = :end
                   AND ( NULLIF(:line, '') IS NULL
                      OR EXISTS (SELECT 1 FROM schemes s
                                  WHERE s.id = contributions.scheme_id
                                    AND s.insurance_line = :line) )
                """)
                .bind("start", req.periodStart())
                .bind("end",   req.periodEnd())
                .bind("line",  lineBind)
                .fetch().rowsUpdated();

        // 5) DELETE the enumerated invoices. Uses the ID list from step
        //    2 so group invoices (scheme_id = NULL) are actually removed.
        //    The FK is already unblocked by step 4.
        Mono<Long> deletedInvoices = targetInvoiceIds.flatMap(ids -> {
            if (ids.isEmpty()) return Mono.just(0L);
            return db.sql("DELETE FROM invoices WHERE id IN (:ids)")
                    .bind("ids", ids)
                    .fetch().rowsUpdated();
        });

        return reverseBalances
                .then(capturePdfPointers)
                .flatMap(pointers -> deletedContributions
                        .flatMap(contributions -> deletedInvoices
                                .flatMap(invoices -> Mono.deferContextual(ctx -> {
                                    String tenantId = TenantContext.get(ctx);
                                    log.info("[revoke] tenant={} period={} to {} line={} deleted invoices={} contributions={} pdfBlobs={}",
                                            tenantId, req.periodStart(), req.periodEnd(), line,
                                            invoices, contributions, pointers.size());

                                    // 6) Tell file-service to delete each MinIO blob
                                    //    that was just orphaned. Fire-and-forget; the
                                    //    revoke succeeds even if Kafka is down.
                                    Mono<Void> publishDeletes = Flux.fromIterable(pointers)
                                            .concatMap(p -> eventPublisher.publishInvoicePdfDeleted(
                                                    tenantId,
                                                    p.invoiceId() == null ? null : p.invoiceId().toString(),
                                                    p.bucket(),
                                                    p.objectKey()))
                                            .then();

                                    return publishDeletes.then(
                                            publishAudit(tenantId, "BillingCycle", "revoke",
                                                    req.periodStart() + " to " + req.periodEnd(),
                                                    "DELETE", actorId, actorEmail,
                                                    Map.of("contributions", String.valueOf(contributions),
                                                            "invoices", String.valueOf(invoices),
                                                            "pdfBlobs", String.valueOf(pointers.size()),
                                                            "periodStart", req.periodStart().toString(),
                                                            "periodEnd", req.periodEnd().toString(),
                                                            "insuranceLine", line == null ? "(all)" : line),
                                                    null))
                                            .thenReturn(new BillingRevokeResponse(
                                                    contributions, invoices,
                                                    req.periodStart(), req.periodEnd(), line, now));
                                }))));
    }

    /** Internal: invoice → MinIO pointer tuple captured before the
     *  DELETE cascade clears the {@code invoice_pdfs} row. */
    private record PdfPointer(UUID invoiceId, String bucket, String objectKey) {}

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
            inv.setDueDate(periodEnd);
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
                                moneyDisplay(saved.getTotalAmount()),
                                saved.getPeriodStart().toString(),
                                saved.getPeriodEnd().toString(),
                                saved.getDueDate().toString(),
                                saved.getCommittedAt() != null ? saved.getCommittedAt().toString() : null,
                                moneyDisplay(saved.getOpeningBalance()),
                                moneyDisplay(saved.getClosingBalance()),
                                moneyDisplay(saved.getPaymentsInWindow()),
                                moneyDisplay(saved.getAdjustmentsInWindow()),
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

    /**
     * Package-visible seam for {@link LateAdjustmentService} and any other
     * caller that needs to know "what would billing charge this member for
     * this month?" without running a full commit. Reuses the same
     * candidate → applyPricing pipeline that {@link #commitBilling} uses,
     * so late-enrolment / scheme-change adjustments post the same amount
     * a normal billing run would have. Returns {@link BigDecimal#ZERO}
     * when the member has no scheme, no active age-group price, or when
     * no candidate resolver handles the scheme's insurance line.
     */
    public Mono<BigDecimal> priceOneMember(UUID memberId, UUID schemeId,
                                    LocalDate periodStart, LocalDate periodEnd) {
        if (memberId == null || schemeId == null || periodStart == null || periodEnd == null) {
            return Mono.just(BigDecimal.ZERO);
        }
        return schemeRepository.findById(schemeId)
                .flatMap(scheme -> {
                    String line = scheme.getInsuranceLine() != null ? scheme.getInsuranceLine() : "HEALTH";
                    return resolveCandidatesForTenant(null, List.of(memberId), periodStart, periodEnd, line)
                            .next()
                            .flatMap(pc -> applyPricing(pc, periodStart, periodEnd))
                            .map(PricedCandidate::amount)
                            .defaultIfEmpty(BigDecimal.ZERO);
                })
                .defaultIfEmpty(BigDecimal.ZERO);
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

    // Package-visible so the pure decoration helper
    // (composeDecoratedLines) can accept it without an @Internal marker
    // and so a targeted unit test in the same package can construct
    // fixtures without reflection.
    record PricedCandidate(
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
    /**
     * Wire-shape display of a money-typed column. Amounts persist at
     * scale 4 for arithmetic headroom, but every outbound presentation
     * surface (invoice email, PDF header, statement page) expects two
     * decimals. Formatting here means the same value lands consistently
     * in every consumer without each having to know the storage scale.
     * Returns null for a null input so the record can preserve
     * "unavailable" semantics for legacy rows.
     */
    private static String moneyDisplay(BigDecimal amount) {
        return amount == null ? null : amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

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
