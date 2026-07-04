package com.medfund.contributions.job;

import com.medfund.contributions.client.UserServiceClient;
import com.medfund.contributions.dto.BadDebtRow;
import com.medfund.contributions.entity.DunningConfig;
import com.medfund.contributions.repository.DunningConfigRepository;
import com.medfund.contributions.service.ArrearsNoticePublisher;
import com.medfund.contributions.service.ArrearsNoticePublisher.ArrearsNoticePayload;
import com.medfund.contributions.service.BalanceService;
import com.medfund.shared.tenant.TenantContext;
import com.medfund.shared.scheduler.JobExecutor;
import com.medfund.shared.scheduler.JobType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Daily arrears-escalation sweep. Runs per-tenant and per-currency:
 *
 * <ul>
 *   <li>Reads {@code dunning_config} — if both {@code auto_suspend} and
 *   {@code auto_write_off} are FALSE, exits silently. Tenants opt in.</li>
 *   <li>Loops each aged row via {@link BalanceService#listAged}. The
 *   service already buckets each row into {@code GRACE / SUSPENDED /
 *   WRITE_OFF} using tenant thresholds.</li>
 *   <li>For a {@code SUSPENDED}-bucketed row: calls
 *   {@link UserServiceClient#suspendMember} or
 *   {@link UserServiceClient#suspendGroup}. Idempotent — user-service
 *   no-ops when the target is already suspended.</li>
 *   <li>For a {@code WRITE_OFF}-bucketed row: calls
 *   {@link UserServiceClient#deactivateMember} /
 *   {@link UserServiceClient#deactivateGroup}. The receiving service
 *   cascades group → members per the plan.</li>
 * </ul>
 *
 * <p><b>Auto-reactivate branch (V043):</b> after the escalation sweep,
 * asks user-service for every row currently in
 * {@code status = 'suspended'} with {@code suspend_reason =
 * 'ARREARS_ESCALATION'} and reactivates any whose id is no longer in
 * {@link BalanceService#currentlyAgedSubjectIds}. That covers the
 * "payment settled the aged debt" case without touching operator-
 * suspended rows (whose suspend_reason is something else).
 * Deactivated rows are not checked here — reactivation from
 * deactivated is a manual action per the plan.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ArrearsEscalationExecutor implements JobExecutor {

    private final DunningConfigRepository dunningRepo;
    private final BalanceService balanceService;
    private final UserServiceClient userClient;
    private final ArrearsNoticePublisher arrearsPublisher;
    private final DatabaseClient db;

    @Override
    public JobType getJobType() {
        return JobType.ARREARS_ESCALATION;
    }

    @Override
    public Mono<Void> execute(String tenantId, String settings) {
        log.info("Arrears escalation sweep for tenant: {}", tenantId);
        return dunningRepo.findById(DunningConfig.SINGLETON_ID)
                .flatMap(config -> {
                    boolean autoSuspend = Boolean.TRUE.equals(config.getAutoSuspend());
                    boolean autoWriteOff = Boolean.TRUE.equals(config.getAutoWriteOff());
                    if (!autoSuspend && !autoWriteOff) {
                        log.info("Tenant {} has auto_suspend + auto_write_off both FALSE — no-op",
                                tenantId);
                        return Mono.empty();
                    }
                    // Two phases:
                    //  1. Escalate — walk currently-aged rows and push
                    //     them up the SUSPENDED → DEACTIVATED ladder.
                    //  2. Reactivate — walk suspended-by-arrears rows
                    //     whose payment has since cleared the age
                    //     threshold and flip them back to active.
                    Mono<Void> escalate = activeCurrencies()
                            .flatMap(currency -> sweepCurrency(currency, config, autoSuspend, autoWriteOff))
                            .then();
                    Mono<Void> reactivate = autoSuspend ? reactivateClearedArrears() : Mono.empty();
                    return escalate.then(reactivate);
                })
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("No dunning_config row for tenant {}", tenantId);
                    return Mono.empty();
                }))
                .then();
    }

    /**
     * Post-escalation sweep: reactivate members and groups that are
     * currently suspended because of arrears and whose aged balance no
     * longer meets the SUSPENDED threshold. "No longer meets" is
     * decided by checking against
     * {@link BalanceService#currentlyAgedSubjectIds} — a row that has
     * paid down enough to drop out of the aged view has by definition
     * cleared the threshold.
     *
     * <p>Deactivated rows are intentionally NOT included: the plan
     * requires deactivation → active to be an explicit operator
     * action, not a payment-triggered auto-flip.
     */
    private Mono<Void> reactivateClearedArrears() {
        return balanceService.currentlyAgedSubjectIds().flatMap(aged -> {
            Mono<Void> members = userClient.listMembersSuspendedForReason("ARREARS_ESCALATION")
                    .filter(id -> !aged.contains(id))
                    .flatMap(id -> userClient.reactivateMember(id, "ARREARS_CLEARED")
                            .doOnSuccess(v -> log.info("Reactivated member {} — arrears cleared", id)))
                    .then();
            Mono<Void> groups = userClient.listGroupsSuspendedForReason("ARREARS_ESCALATION")
                    .filter(id -> !aged.contains(id))
                    .flatMap(id -> userClient.reactivateGroup(id, "ARREARS_CLEARED")
                            .doOnSuccess(v -> log.info("Reactivated group {} — arrears cleared", id)))
                    .then();
            return members.then(groups);
        });
    }

    private Flux<String> activeCurrencies() {
        return db.sql("""
                    SELECT currency_code FROM tenant_currency_config
                     WHERE is_active = TRUE
                    """)
                .map(row -> row.get("currency_code", String.class))
                .all()
                .switchIfEmpty(Flux.just("USD"));
    }

    private Mono<Void> sweepCurrency(String currency, DunningConfig config,
                                       boolean autoSuspend, boolean autoWriteOff) {
        int pageSize = 200;
        return sweepPages(currency, config, autoSuspend, autoWriteOff, 0, pageSize);
    }

    /**
     * Walk pages sequentially until an empty page is returned. Sequential
     * (not parallel) so a tenant with tens of thousands of aged rows
     * doesn't hammer user-service with thousands of concurrent PATCHes.
     */
    private Mono<Void> sweepPages(String currency, DunningConfig config,
                                    boolean autoSuspend, boolean autoWriteOff,
                                    int page, int pageSize) {
        return balanceService.listAged(currency, null, null, page, pageSize)
                .flatMap(pg -> {
                    if (pg.content().isEmpty()) return Mono.<Void>empty();
                    return Flux.fromIterable(pg.content())
                            .flatMap(row -> processRow(row, config, autoSuspend, autoWriteOff))
                            .then(pg.content().size() < pageSize
                                    ? Mono.<Void>empty()
                                    : sweepPages(currency, config, autoSuspend, autoWriteOff,
                                            page + 1, pageSize));
                });
    }

    /**
     * Per-row driver: decides whether today is a reminder tick,
     * whether to escalate the status, and publishes the corresponding
     * arrears-notice on either path. Every branch is a no-op if the
     * corresponding tenant flag is off.
     */
    private Mono<Void> processRow(BadDebtRow row, DunningConfig config,
                                    boolean autoSuspend, boolean autoWriteOff) {
        String bucket = row.agingStatus();
        UUID subjectId = row.subjectId();
        String subjectType = row.subjectType();
        boolean isGroup = "GROUP".equals(subjectType);
        long daysOverdue = row.daysSinceLastActivity() != null ? row.daysSinceLastActivity() : 0;
        int suspensionDays = config.getSuspensionDays() != null ? config.getSuspensionDays() : 30;
        int deactivationDays = config.getDeactivationDays() != null ? config.getDeactivationDays() : 90;

        // Reminders are the ONLY event this executor publishes. The
        // "your account has been suspended / deactivated" email is
        // fired by the lifecycle consumer in notification-service when
        // it sees the resulting MEMBER_STATUS_CHANGED /
        // GROUP_STATUS_CHANGED event — that way arrears-triggered
        // flips and operator-triggered flips share one email path.
        Mono<Void> escalate = Mono.empty();
        Mono<Void> reminder = Mono.empty();

        if ("SUSPENDED".equals(bucket) && autoSuspend) {
            escalate = isGroup
                    ? userClient.suspendGroup(subjectId, null, "ARREARS_ESCALATION")
                    : userClient.suspendMember(subjectId, null, "ARREARS_ESCALATION");
        } else if ("WRITE_OFF".equals(bucket) && autoWriteOff) {
            escalate = isGroup
                    ? userClient.deactivateGroup(subjectId, null, "ARREARS_ESCALATION")
                    : userClient.deactivateMember(subjectId, null, "ARREARS_ESCALATION");
        } else if (Boolean.TRUE.equals(config.getAutoRemind())
                && shouldRemindToday(bucket, daysOverdue, suspensionDays, config)) {
            String kind = "GRACE".equals(bucket)
                    ? ArrearsNoticePublisher.KIND_REMINDER_PRE_SUSPENSION
                    : ArrearsNoticePublisher.KIND_REMINDER_PRE_DEACTIVATION;
            long daysUntilNext = "GRACE".equals(bucket)
                    ? Math.max(suspensionDays - daysOverdue, 0)
                    : Math.max(deactivationDays - daysOverdue, 0);
            String nextStep = "GRACE".equals(bucket) ? "SUSPENSION" : "DEACTIVATION";
            reminder = publishNotice(row, kind, daysUntilNext, nextStep);
        }

        return escalate.then(reminder);
    }

    /**
     * True when today's daysOverdue lands on a reminder tick. Ticks
     * begin at {@code suspensionDays - reminderLeadDays} and repeat
     * every {@code reminderIntervalDays}. Post-suspension ticks
     * (bucket == SUSPENDED) only fire if
     * {@code reminderContinuePastSuspension} is on.
     */
    private static boolean shouldRemindToday(String bucket, long daysOverdue,
                                              int suspensionDays, DunningConfig config) {
        if ("WRITE_OFF".equals(bucket)) return false;  // no reminders after deactivation
        boolean isPastSuspension = "SUSPENDED".equals(bucket);
        if (isPastSuspension && !Boolean.TRUE.equals(config.getReminderContinuePastSuspension()))
            return false;
        int leadDays = config.getReminderLeadDays() != null ? config.getReminderLeadDays() : 7;
        int interval = config.getReminderIntervalDays() != null ? config.getReminderIntervalDays() : 3;
        if (interval < 1) interval = 1;
        long firstTick = Math.max(suspensionDays - leadDays, 0);
        if (daysOverdue < firstTick) return false;
        return ((daysOverdue - firstTick) % interval) == 0;
    }

    private Mono<Void> publishNotice(BadDebtRow row, String kind,
                                       Long daysUntilNextStep, String nextStep) {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            return arrearsPublisher.publish(new ArrearsNoticePayload(
                    tenantId,
                    kind,
                    row.subjectType(),
                    row.subjectId().toString(),
                    row.currencyCode(),
                    row.balance(),
                    row.daysSinceLastActivity() != null ? row.daysSinceLastActivity() : 0L,
                    daysUntilNextStep,
                    nextStep));
        });
    }
}
