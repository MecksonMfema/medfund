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
                            saved.getDueDate().toString())))
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
        return resolveCandidatesForTenant(req.groupIds(), req.memberIds(), req.periodStart(), req.periodEnd())
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
                    return doCommit(req, actorId, actorEmail, cfg);
                });
    }

    private Mono<BillingCommitResponse> doCommit(CommitBillingRequest req, String actorId, String actorEmail,
                                                  BillingCycleConfig cfg) {
        UUID actorUuid = parseUuid(actorId);
        Instant now = Instant.now();

        return resolveCandidatesForTenant(req.groupIds(), req.memberIds(), req.periodStart(), req.periodEnd())
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
                        return getMembershipModel(tenantId).flatMap(model ->
                                generateInvoicesFor(saved, model, actorUuid,
                                        req.periodStart(), req.periodEnd()));
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
                                                    UUID actorUuid, LocalDate periodStart, LocalDate periodEnd) {
        if (contributions.isEmpty()) return Mono.just(List.of());

        Map<RoutingKey, List<Contribution>> buckets = new LinkedHashMap<>();
        for (Contribution c : contributions) {
            RoutingKey key = computeRoutingKey(c, model);
            buckets.computeIfAbsent(key, k -> new ArrayList<>()).add(c);
        }

        return Flux.fromIterable(buckets.entrySet())
                .concatMap(e -> persistInvoiceFor(e.getKey(), e.getValue(), actorUuid, periodStart, periodEnd))
                .collectList();
    }

    private Mono<Invoice> persistInvoiceFor(RoutingKey key, List<Contribution> rows, UUID actorUuid,
                                            LocalDate periodStart, LocalDate periodEnd) {
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
            inv.setIssuedAt(Instant.now());
            inv.setDueDate(periodEnd.plusDays(30));
            inv.setCreatedAt(Instant.now());
            inv.setUpdatedAt(Instant.now());
            inv.setCreatedBy(actorUuid);
            return invoiceRepository.save(inv);
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
            // Fan out to file-service (PDF rendering) and onward to
            // notification-service (email delivery). Fire-and-forget so the
            // commit response stays snappy — failures are visible in
            // audit + NotificationSent events, not blocking on Kafka latency.
            eventPublisher.publishInvoiceIssued(
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
                            saved.getDueDate().toString()))
                .thenReturn(saved)));
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
     * Per-tenant entry point: reads the tenant's pricing_model from
     * public.tenants and delegates to the SQL builder. Streams through
     * a deferred lookup so the candidates Flux only starts after the
     * model resolves.
     */
    private Flux<PersonCandidate> resolveCandidatesForTenant(List<UUID> groupIds, List<UUID> memberIds,
                                                              LocalDate periodStart, LocalDate periodEnd) {
        return Mono.deferContextual(ctx -> getPricingModel(TenantContext.get(ctx)))
                .flatMapMany(mode -> resolveCandidates(groupIds, memberIds, periodStart, periodEnd, mode));
    }

    /**
     * Walks the {@code members} table joined to {@code schemes}, applying the
     * optional group / member filters. Returned candidates carry just enough
     * metadata for pricing + the wizard preview. Schemes are always included
     * in full — billing covers every active scheme by design.
     *
     * <p>{@code pricingModel} ("STANDARD" or "INDIVIDUAL") gates the
     * per-member override CASE. STANDARD ignores overrides entirely;
     * INDIVIDUAL applies them when set and effective.
     */
    private Flux<PersonCandidate> resolveCandidates(List<UUID> groupIds, List<UUID> memberIds,
                                                    LocalDate periodStart, LocalDate periodEnd,
                                                    String pricingModel) {
        // One row per INSURED PERSON — the member's own line plus a line per
        // active dependant — with the effective age-group resolved via
        // COALESCE(billing_override, canonical) gated by the override's
        // effective_from date. Pricing is read directly from the matched
        // age_groups row; the tenant pricing rules later get a chance to
        // override the amount inside applyPricing().
        //
        // dependant_id IS NULL on the member's line; it is set on the
        // dependant's line. member_id is always the parent member used for
        // invoice routing.
        // Price lookup: LATERAL join to the age_group_prices row that
        // was active for this billing period — the row whose
        // [effective_from, effective_to] range overlaps [periodStart,
        // periodEnd]. This keeps retrospective runs deterministic
        // even after the tenant edits the price; today's amount is
        // not retroactively applied to last month's bill.
        StringBuilder sql = new StringBuilder("""
                SELECT m.id                AS member_id,
                       NULL::uuid          AS dependant_id,
                       m.member_number     AS member_number,
                       (m.first_name || ' ' || m.last_name) AS person_name,
                       'MEMBER'            AS person_type,
                       m.scheme_id         AS scheme_id,
                       m.group_id          AS group_id,
                       g.name              AS group_name,
                       s.name              AS scheme_name,
                       s.currency_code     AS scheme_currency,
                       CASE
                           WHEN m.billing_age_group_id IS NOT NULL
                                AND (m.billing_override_effective_from IS NULL
                                     OR m.billing_override_effective_from <= :periodStart)
                           THEN m.billing_age_group_id
                           ELSE m.age_group_id
                       END                 AS effective_age_group_id,
                       ag.name              AS age_band_name,
                       -- Per-member override gated on the tenant's
                       -- pricing_model (V118). STANDARD ignores overrides
                       -- entirely; INDIVIDUAL honours an override when
                       -- set and effective. Currency stays with the
                       -- scheme-default source — billing_override_amount
                       -- is just a number.
                       COALESCE(
                           CASE WHEN :pricingModel = 'INDIVIDUAL'
                                     AND m.billing_override_amount IS NOT NULL
                                     AND (m.billing_override_effective_from IS NULL
                                          OR m.billing_override_effective_from <= :periodEnd)
                                THEN m.billing_override_amount END,
                           p.contribution_amount) AS price_amount,
                       p.currency_code       AS price_currency
                  FROM members m
                  JOIN schemes s     ON s.id = m.scheme_id
                  LEFT JOIN groups g ON g.id = m.group_id
                  LEFT JOIN age_groups ag ON ag.id = CASE
                           WHEN m.billing_age_group_id IS NOT NULL
                                AND (m.billing_override_effective_from IS NULL
                                     OR m.billing_override_effective_from <= :periodStart)
                           THEN m.billing_age_group_id
                           ELSE m.age_group_id
                       END
                  LEFT JOIN LATERAL (
                       SELECT contribution_amount, currency_code
                         FROM age_group_prices
                        WHERE age_group_id = ag.id
                          AND effective_from <= :periodEnd
                          AND (effective_to IS NULL OR effective_to >= :periodStart)
                        ORDER BY effective_from DESC
                        LIMIT 1
                  ) p ON TRUE
                 -- Billing covers active + suspended members so a brief
                 -- subscription pause doesn't drop them out of arrears
                 -- tracking. Terminated / closed members are excluded.
                 -- Group-routed members additionally require their group
                 -- to be active — a non-active group has no liaison to
                 -- invoice and its membership is effectively frozen.
                 --
                 -- enrollment_date gates retrospective runs: a member
                 -- enrolled in June must not appear in May's bill. The
                 -- DB enforces enrollment is always 1st-of-month (see
                 -- members_enrollment_date_first_of_month constraint),
                 -- so comparing against periodEnd is sufficient — anyone
                 -- enrolled by the last day of the billing period
                 -- qualifies for that period and every period after.
                 WHERE m.status IN ('active', 'suspended')
                   AND (m.group_id IS NULL OR g.status = 'active')
                   AND m.enrollment_date <= :periodEnd
                """);
        if (groupIds  != null && !groupIds.isEmpty())  sql.append(" AND m.group_id = ANY(:groupIds) ");
        if (memberIds != null && !memberIds.isEmpty()) sql.append(" AND m.id       = ANY(:memberIds) ");

        sql.append("""

                UNION ALL

                SELECT m.id                AS member_id,
                       d.id                AS dependant_id,
                       m.member_number     AS member_number,
                       (d.first_name || ' ' || d.last_name) AS person_name,
                       'DEPENDANT'         AS person_type,
                       m.scheme_id         AS scheme_id,
                       m.group_id          AS group_id,
                       g.name              AS group_name,
                       s.name              AS scheme_name,
                       s.currency_code     AS scheme_currency,
                       CASE
                           WHEN d.billing_age_group_id IS NOT NULL
                                AND (d.billing_override_effective_from IS NULL
                                     OR d.billing_override_effective_from <= :periodStart)
                           THEN d.billing_age_group_id
                           ELSE d.age_group_id
                       END                 AS effective_age_group_id,
                       ag.name              AS age_band_name,
                       -- Same gating as the member branch: STANDARD
                       -- ignores the dependant's override; INDIVIDUAL
                       -- honours it when set and effective.
                       COALESCE(
                           CASE WHEN :pricingModel = 'INDIVIDUAL'
                                     AND d.billing_override_amount IS NOT NULL
                                     AND (d.billing_override_effective_from IS NULL
                                          OR d.billing_override_effective_from <= :periodEnd)
                                THEN d.billing_override_amount END,
                           p.contribution_amount) AS price_amount,
                       p.currency_code       AS price_currency
                  FROM dependants d
                  JOIN members m     ON m.id = d.member_id
                  JOIN schemes s     ON s.id = m.scheme_id
                  LEFT JOIN groups g ON g.id = m.group_id
                  LEFT JOIN age_groups ag ON ag.id = CASE
                           WHEN d.billing_age_group_id IS NOT NULL
                                AND (d.billing_override_effective_from IS NULL
                                     OR d.billing_override_effective_from <= :periodStart)
                           THEN d.billing_age_group_id
                           ELSE d.age_group_id
                       END
                  LEFT JOIN LATERAL (
                       SELECT contribution_amount, currency_code
                         FROM age_group_prices
                        WHERE age_group_id = ag.id
                          AND effective_from <= :periodEnd
                          AND (effective_to IS NULL OR effective_to >= :periodStart)
                        ORDER BY effective_from DESC
                        LIMIT 1
                  ) p ON TRUE
                 -- Dependants follow the same active+suspended rule as
                 -- their parent member; both must qualify for the
                 -- dependant line to bill. Dependants have no
                 -- enrollment_date column — created_at::date is the
                 -- best available proxy for "when they joined the
                 -- household on cover". They also can't bill earlier
                 -- than their parent member's enrollment_date.
                 WHERE d.status IN ('active', 'suspended')
                   AND m.status IN ('active', 'suspended')
                   AND (m.group_id IS NULL OR g.status = 'active')
                   AND m.enrollment_date <= :periodEnd
                   AND d.created_at::date <= :periodEnd
                """);
        if (groupIds  != null && !groupIds.isEmpty())  sql.append(" AND m.group_id = ANY(:groupIds) ");
        if (memberIds != null && !memberIds.isEmpty()) sql.append(" AND m.id       = ANY(:memberIds) ");

        var spec = db.sql(sql.toString())
                .bind("periodStart",  periodStart)
                .bind("periodEnd",    periodEnd)
                .bind("pricingModel", pricingModel != null ? pricingModel : "STANDARD");
        if (groupIds  != null && !groupIds.isEmpty())  spec = spec.bind("groupIds",  groupIds.toArray(UUID[]::new));
        if (memberIds != null && !memberIds.isEmpty()) spec = spec.bind("memberIds", memberIds.toArray(UUID[]::new));

        return spec.map(row -> new PersonCandidate(
                        row.get("member_id", UUID.class),
                        row.get("dependant_id", UUID.class),
                        row.get("member_number", String.class),
                        row.get("person_name", String.class),
                        row.get("person_type", String.class),
                        row.get("scheme_id", UUID.class),
                        row.get("scheme_name", String.class),
                        row.get("group_id", UUID.class),
                        row.get("group_name", String.class),
                        row.get("scheme_currency", String.class),
                        row.get("effective_age_group_id", UUID.class),
                        row.get("age_band_name", String.class),
                        row.get("price_amount", BigDecimal.class),
                        row.get("price_currency", String.class)))
                .all();
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
                .thenReturn(new PricedCandidate(
                        p.memberId(), p.dependantId(), p.memberNumber(), p.personName(), p.personType(),
                        p.schemeId(), p.schemeName(), p.groupId(), p.groupName(),
                        p.effectiveAgeGroupId(), p.ageBandName(),
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

    /**
     * Per-person row produced by {@link #resolveCandidates}. {@code memberId}
     * is the parent member for invoice routing and is always set;
     * {@code dependantId} is set only when this row is for the dependant.
     * {@code priceAmount}/{@code priceCurrency} come from the matched
     * {@code age_groups} row — they may be null when the person has no
     * canonical bucket (data gap), which {@link #applyPricing} treats as
     * zero so the row still surfaces in the preview.
     */
    private record PersonCandidate(
            UUID memberId, UUID dependantId, String memberNumber, String personName, String personType,
            UUID schemeId, String schemeName, UUID groupId, String groupName, String schemeCurrency,
            UUID effectiveAgeGroupId, String ageBandName,
            BigDecimal priceAmount, String priceCurrency) {}

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
        CommitBillingRequest req = new CommitBillingRequest(periodStart, periodEnd, null, null);
        return commitBilling(req, AuditActor.SYSTEM_ID, AuditActor.SYSTEM_EMAIL)
            .doOnNext(resp -> log.info("Auto billing committed {} contributions across {} group + {} member invoices",
                resp.contributionsCreated(), resp.groupInvoicesCreated(), resp.individualInvoicesCreated()))
            .then();
    }
}
