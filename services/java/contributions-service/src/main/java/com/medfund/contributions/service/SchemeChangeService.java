package com.medfund.contributions.service;

import com.medfund.contributions.dto.SchemeChangeRequest;
import com.medfund.contributions.entity.Scheme;
import com.medfund.contributions.entity.SchemeChange;
import com.medfund.contributions.entity.SchemeChangeWaitingPeriodRule;
import com.medfund.contributions.repository.CurrencyChangeWaitingPeriodRuleRepository;
import com.medfund.contributions.repository.SchemeChangeRepository;
import com.medfund.contributions.repository.SchemeChangeWaitingPeriodRuleRepository;
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

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

@Service
public class SchemeChangeService {

    private static final Logger log = LoggerFactory.getLogger(SchemeChangeService.class);

    private final SchemeChangeRepository schemeChangeRepository;
    private final SchemeRepository schemeRepository;
    private final SchemeChangeWaitingPeriodRuleRepository waitingPeriodRuleRepository;
    private final CurrencyChangeWaitingPeriodRuleRepository currencyWaitingRuleRepository;
    private final AuditPublisher auditPublisher;
    private final ContributionEventPublisher eventPublisher;
    private final DatabaseClient db;

    public SchemeChangeService(SchemeChangeRepository schemeChangeRepository,
                               SchemeRepository schemeRepository,
                               SchemeChangeWaitingPeriodRuleRepository waitingPeriodRuleRepository,
                               CurrencyChangeWaitingPeriodRuleRepository currencyWaitingRuleRepository,
                               AuditPublisher auditPublisher,
                               ContributionEventPublisher eventPublisher,
                               DatabaseClient db) {
        this.schemeChangeRepository = schemeChangeRepository;
        this.schemeRepository = schemeRepository;
        this.waitingPeriodRuleRepository = waitingPeriodRuleRepository;
        this.currencyWaitingRuleRepository = currencyWaitingRuleRepository;
        this.auditPublisher = auditPublisher;
        this.eventPublisher = eventPublisher;
        this.db = db;
    }

    public Flux<SchemeChange> findByMemberId(UUID memberId) {
        return schemeChangeRepository.findByMemberId(memberId);
    }

    public Flux<SchemeChange> findPending() {
        return schemeChangeRepository.findByStatus("PENDING");
    }

    /**
     * Book a scheme change. V048 semantics:
     * <ul>
     *   <li>Classifies the change kind (UPGRADE / DOWNGRADE / CURRENCY_CHANGE
     *       / CROSS_GRADE) by comparing the from/to schemes' currency
     *       and — for same-currency changes — a placeholder for future
     *       benefit-sum comparison. Callers may override via
     *       {@link SchemeChangeRequest#changeKind()}; the auto-classifier
     *       only runs when the caller left it null.</li>
     *   <li>Applies the tenant's {@code scheme_change_waiting_period_rules}
     *       by auto-pushing the requested effective_date forward when a
     *       rule for the classified kind exists. The DTO carries the
     *       adjusted date on the response so the frontend can render
     *       "Effective 1 Sep 2026" instead of the requester's original.</li>
     *   <li>Back-dating (effective_date &lt; current month) bypasses the
     *       PENDING → APPROVED → APPLIED workflow — the row lands as
     *       APPLIED and a SCHEME_CHANGED event fires with
     *       {@code backdated=true} so {@link
     *       com.medfund.contributions.consumer.SchemeChangedConsumer}
     *       posts arrears/rebate on the already-billed months.</li>
     * </ul>
     */
    @Transactional
    public Mono<SchemeChange> request(SchemeChangeRequest request, String actorId, String actorEmail) {
        return classify(request)
                .flatMap(classification -> resolveEffectiveDate(request, classification)
                        .map(effective -> buildRow(request, classification.kind(), effective, actorId)))
                .flatMap(row -> {
                    LocalDate currentMonth = LocalDate.now().withDayOfMonth(1);
                    if (row.getEffectiveDate() != null
                            && row.getEffectiveDate().isBefore(currentMonth)) {
                        return applyImmediately(row, actorId, actorEmail);
                    }
                    return persistPending(row, actorId, actorEmail);
                });
    }

    @Transactional
    public Mono<SchemeChange> approve(UUID id, String actorId, String actorEmail) {
        return schemeChangeRepository.findById(id)
            .switchIfEmpty(Mono.error(new IllegalArgumentException("Scheme change not found: " + id)))
            .flatMap(sc -> {
                if (!"PENDING".equals(sc.getStatus())) {
                    return Mono.error(new IllegalStateException(
                        "Scheme change " + id + " is not PENDING, current status: " + sc.getStatus()));
                }

                String previousStatus = sc.getStatus();
                sc.setStatus("APPROVED");
                sc.setApprovedBy(safeUuid(actorId));
                sc.setApprovedAt(Instant.now());
                sc.setUpdatedAt(Instant.now());

                return schemeChangeRepository.save(sc)
                    .flatMap(saved -> publishAuditEvent(saved, "UPDATE", actorId, actorEmail,
                            Map.of("status", previousStatus),
                            Map.of("status", saved.getStatus(),
                                   "approvedBy", actorId))
                            .thenReturn(saved));
            });
    }

    @Transactional
    public Mono<SchemeChange> reject(UUID id, String reason, String actorId, String actorEmail) {
        return schemeChangeRepository.findById(id)
            .switchIfEmpty(Mono.error(new IllegalArgumentException("Scheme change not found: " + id)))
            .flatMap(sc -> {
                if (!"PENDING".equals(sc.getStatus())) {
                    return Mono.error(new IllegalStateException(
                        "Scheme change " + id + " is not PENDING, current status: " + sc.getStatus()));
                }

                String previousStatus = sc.getStatus();
                sc.setStatus("REJECTED");
                sc.setRejectionReason(reason);
                sc.setUpdatedAt(Instant.now());

                return schemeChangeRepository.save(sc)
                    .flatMap(saved -> publishAuditEvent(saved, "UPDATE", actorId, actorEmail,
                            Map.of("status", previousStatus),
                            Map.of("status", saved.getStatus(),
                                   "rejectionReason", reason == null ? "" : reason))
                            .thenReturn(saved));
            });
    }

    @Transactional
    public Mono<SchemeChange> makeEffective(UUID id, String actorId, String actorEmail) {
        return schemeChangeRepository.findById(id)
            .switchIfEmpty(Mono.error(new IllegalArgumentException("Scheme change not found: " + id)))
            .flatMap(sc -> {
                if (!"APPROVED".equals(sc.getStatus())) {
                    return Mono.error(new IllegalStateException(
                        "Scheme change " + id + " is not APPROVED, current status: " + sc.getStatus()));
                }
                return applyToMember(sc, actorId, actorEmail);
            });
    }

    // ── Apply — shared by makeEffective (forward-dated) + request (back-dated) ─

    private Mono<SchemeChange> applyToMember(SchemeChange sc, String actorId, String actorEmail) {
        String previousStatus = sc.getStatus();
        sc.setStatus("EFFECTIVE");
        sc.setUpdatedAt(Instant.now());
        return flipMemberSchemeAndClearOverride(sc)
                .then(schemeChangeRepository.save(sc))
                .flatMap(saved -> publishAuditEvent(saved, "UPDATE", actorId, actorEmail,
                        Map.of("status", previousStatus),
                        Map.of("status", saved.getStatus()))
                        .then(publishSchemeChanged(saved, false))
                        .thenReturn(saved));
    }

    /**
     * Fast-path for back-dated requests. Skips PENDING → APPROVED and
     * writes the row directly as EFFECTIVE, flips the member's scheme
     * on the members table, publishes SCHEME_CHANGED with
     * {@code backdated=true} so the consumer posts arrears/rebate.
     */
    private Mono<SchemeChange> applyImmediately(SchemeChange row, String actorId, String actorEmail) {
        row.setStatus("EFFECTIVE");
        row.setApprovedBy(safeUuid(actorId));
        row.setApprovedAt(Instant.now());
        return flipMemberSchemeAndClearOverride(row)
                .then(schemeChangeRepository.save(row))
                .flatMap(saved -> publishAuditEvent(saved, "CREATE", actorId, actorEmail, null,
                        Map.of("memberId", saved.getMemberId().toString(),
                               "fromSchemeId", saved.getFromSchemeId().toString(),
                               "toSchemeId", saved.getToSchemeId().toString(),
                               "status", saved.getStatus(),
                               "changeKind", saved.getChangeKind(),
                               "backdated", "true"))
                        .then(publishSchemeChanged(saved, true))
                        .thenReturn(saved));
    }

    private Mono<SchemeChange> persistPending(SchemeChange row, String actorId, String actorEmail) {
        row.setStatus("PENDING");
        return schemeChangeRepository.save(row)
                .flatMap(saved -> publishAuditEvent(saved, "CREATE", actorId, actorEmail, null,
                        Map.of("memberId", saved.getMemberId().toString(),
                               "fromSchemeId", saved.getFromSchemeId().toString(),
                               "toSchemeId", saved.getToSchemeId().toString(),
                               "status", saved.getStatus(),
                               "effectiveDate", saved.getEffectiveDate().toString(),
                               "changeKind", saved.getChangeKind()))
                        .thenReturn(saved));
    }

    // ── Classification helper ────────────────────────────────────────

    /**
     * Result of the classify step. Carries the from/to schemes when
     * they were resolved so downstream waiting-period lookups can
     * consult the target currency without a second fetch.
     */
    record Classification(String kind, Scheme fromScheme, Scheme toScheme) {}

    private Mono<Classification> classify(SchemeChangeRequest req) {
        return Mono.zip(
                schemeRepository.findById(req.fromSchemeId()),
                schemeRepository.findById(req.toSchemeId()))
                .map(t -> {
                    Scheme from = t.getT1();
                    Scheme to = t.getT2();
                    // Caller-supplied override wins the KIND classification;
                    // the resolved schemes still ride along so
                    // resolveEffectiveDate can do its currency lookup.
                    if (req.changeKind() != null && !req.changeKind().isBlank()) {
                        return new Classification(req.changeKind(), from, to);
                    }
                    return new Classification(classifyByCurrency(from, to), from, to);
                })
                .defaultIfEmpty(new Classification(
                        req.changeKind() != null ? req.changeKind() : "CROSS_GRADE",
                        null, null));
    }

    private static String classifyByCurrency(Scheme from, Scheme to) {
        String fromCur = from.getCurrencyCode();
        String toCur = to.getCurrencyCode();
        if (fromCur != null && toCur != null && !fromCur.equalsIgnoreCase(toCur)) {
            return "CURRENCY_CHANGE";
        }
        // Same currency — deeper UPGRADE/DOWNGRADE classification would
        // compare SchemeBenefit annual_limit sums. Deferred; falls
        // through to CROSS_GRADE so no automatic ledger post fires.
        return "CROSS_GRADE";
    }

    // ── Waiting-period auto-push ─────────────────────────────────────

    /**
     * V048 Gap B + user-feedback follow-up: direction-specific
     * currency change waiting periods (V049).
     *
     * <ul>
     *   <li>UPGRADE / DOWNGRADE → consult
     *       {@code scheme_change_waiting_period_rules}. Take the MAX
     *       waiting_days across matches (most restrictive wins).</li>
     *   <li>CURRENCY_CHANGE → consult
     *       {@code currency_change_waiting_period_rules} for the
     *       specific (fromCurrency, toCurrency) pair. A missing row
     *       means "no wait" — critically, ZWG→USD and USD→ZWG are
     *       independent lookups; a rule for one direction does not
     *       apply to the other.</li>
     *   <li>CROSS_GRADE → no auto-push.</li>
     * </ul>
     *
     * <p>When a wait applies the effective_date is pushed forward to
     * {@code today + waiting_days} and snapped to the 1st of that
     * month (all billing operations align on month starts).
     */
    private Mono<LocalDate> resolveEffectiveDate(SchemeChangeRequest req, Classification classification) {
        LocalDate requested = req.effectiveDate();
        if (requested == null) return Mono.just(LocalDate.now().withDayOfMonth(1));
        String changeKind = classification.kind();
        if ("UPGRADE".equals(changeKind) || "DOWNGRADE".equals(changeKind)) {
            return maxUpgradeDowngradeWait(changeKind)
                    .map(days -> pushForward(requested, days))
                    .defaultIfEmpty(requested);
        }
        if ("CURRENCY_CHANGE".equals(changeKind)) {
            Scheme from = classification.fromScheme();
            Scheme to = classification.toScheme();
            if (from == null || to == null
                    || from.getCurrencyCode() == null || to.getCurrencyCode() == null) {
                return Mono.just(requested);
            }
            return currencyWaitingRuleRepository
                    .findActive(from.getCurrencyCode().toUpperCase(),
                                to.getCurrencyCode().toUpperCase())
                    .map(rule -> pushForward(requested, rule.getWaitingDays()))
                    .defaultIfEmpty(requested);
        }
        return Mono.just(requested);
    }

    private Mono<Integer> maxUpgradeDowngradeWait(String changeKind) {
        return waitingPeriodRuleRepository.findAllOrdered()
                .filter(r -> Boolean.TRUE.equals(r.getIsActive())
                        && changeKind.equalsIgnoreCase(r.getChangeType()))
                .map(SchemeChangeWaitingPeriodRule::getWaitingDays)
                .reduce(Math::max);
    }

    private static LocalDate pushForward(LocalDate requested, Integer days) {
        if (days == null || days <= 0) return requested;
        LocalDate earliest = LocalDate.now().plusDays(days).withDayOfMonth(1);
        return requested.isBefore(earliest) ? earliest : requested;
    }

    // ── Persistence helpers ──────────────────────────────────────────

    private static SchemeChange buildRow(SchemeChangeRequest req, String changeKind,
                                          LocalDate effective, String actorId) {
        SchemeChange row = new SchemeChange();
        row.setMemberId(req.memberId());
        row.setFromSchemeId(req.fromSchemeId());
        row.setToSchemeId(req.toSchemeId());
        row.setRequestedDate(LocalDate.now());
        row.setEffectiveDate(effective);
        row.setReason(req.reason());
        row.setCreatedAt(Instant.now());
        row.setUpdatedAt(Instant.now());
        row.setCreatedBy(safeUuid(actorId));
        row.setChangeKind(changeKind);
        return row;
    }

    /**
     * V048 Gap C — carry-over. Flip {@code members.scheme_id} on the
     * members table AND null out {@code billing_age_group_id} +
     * override effective_from because the pointer refers to an
     * {@code age_groups} row scoped to the OLD scheme. The new scheme's
     * band lookup happens dynamically at billing time via the resolver's
     * COALESCE fallback (V048 comment on HealthCandidateResolver).
     */
    private Mono<Void> flipMemberSchemeAndClearOverride(SchemeChange sc) {
        return db.sql("""
                UPDATE members
                   SET scheme_id = :toScheme,
                       billing_age_group_id = NULL,
                       billing_override_effective_from = NULL,
                       updated_at = NOW()
                 WHERE id = :memberId
                """)
                .bind("toScheme", sc.getToSchemeId())
                .bind("memberId", sc.getMemberId())
                .fetch().rowsUpdated()
                .doOnNext(rows -> {
                    if (rows == null || rows == 0L) {
                        log.warn("Scheme change {} — no members row updated for member {}",
                                sc.getId(), sc.getMemberId());
                    }
                })
                .then();
    }

    private Mono<Void> publishSchemeChanged(SchemeChange saved, boolean backdated) {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            return eventPublisher.publishSchemeChanged(
                    new ContributionEventPublisher.SchemeChangedPayload(
                            saved.getId().toString(),
                            saved.getMemberId() != null ? saved.getMemberId().toString() : null,
                            tenantId,
                            saved.getFromSchemeId() != null ? saved.getFromSchemeId().toString() : null,
                            saved.getToSchemeId()   != null ? saved.getToSchemeId().toString()   : null,
                            saved.getEffectiveDate() != null ? saved.getEffectiveDate().toString() : null,
                            saved.getChangeKind() != null ? saved.getChangeKind() : "CROSS_GRADE",
                            backdated));
        });
    }

    // ── Audit helpers ────────────────────────────────────────────────

    private Mono<Void> publishAuditEvent(SchemeChange saved, String action,
                                          String actorId, String actorEmail,
                                          Map<String, Object> oldValue, Map<String, Object> newValue) {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            return publishAudit(tenantId, "SchemeChange", saved.getId().toString(),
                    schemeChangeName(saved), action, actorId, actorEmail,
                    oldValue, newValue);
        });
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

    private static String schemeChangeName(SchemeChange sc) {
        String member = sc.getMemberId() != null ? sc.getMemberId().toString() : "?";
        String from = sc.getFromSchemeId() != null ? sc.getFromSchemeId().toString() : "?";
        String to = sc.getToSchemeId() != null ? sc.getToSchemeId().toString() : "?";
        String kind = sc.getChangeKind() != null ? sc.getChangeKind() : "CROSS_GRADE";
        return "member " + member + " " + from + " -> " + to + " (" + kind + ")";
    }

    private static UUID safeUuid(String s) {
        if (s == null) return null;
        try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
    }
}
