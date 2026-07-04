package com.medfund.contributions.job;

import com.medfund.contributions.client.UserServiceClient;
import com.medfund.contributions.entity.DunningConfig;
import com.medfund.contributions.repository.DunningConfigRepository;
import com.medfund.contributions.service.BalanceService;
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
 * <p>The "payment clears suspension" branch is a follow-up: it needs
 * a query for members/groups currently in {@code suspended} with
 * {@code scheduled_status_reason = 'ARREARS_ESCALATION'} whose aged
 * balance no longer meets the SUSPENDED threshold. Leaving a TODO
 * for the next iteration keeps this executor's scope tight for the
 * first ship — the "arrears → suspended → deactivated" ladder is
 * live now; auto-reactivation follows on the same cadence.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ArrearsEscalationExecutor implements JobExecutor {

    private final DunningConfigRepository dunningRepo;
    private final BalanceService balanceService;
    private final UserServiceClient userClient;
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
                    // Query the tenant's active currencies then sweep each.
                    return activeCurrencies()
                            .flatMap(currency -> sweepCurrency(currency, config, autoSuspend, autoWriteOff))
                            .then();
                })
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("No dunning_config row for tenant {}", tenantId);
                    return Mono.empty();
                }))
                .then();
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
                            .flatMap(row -> escalateRow(row, autoSuspend, autoWriteOff))
                            .then(pg.content().size() < pageSize
                                    ? Mono.<Void>empty()
                                    : sweepPages(currency, config, autoSuspend, autoWriteOff,
                                            page + 1, pageSize));
                });
    }

    private Mono<Void> escalateRow(com.medfund.contributions.dto.BadDebtRow row,
                                    boolean autoSuspend, boolean autoWriteOff) {
        String bucket = row.agingStatus();
        String subjectType = row.subjectType();  // "MEMBER" or "GROUP"
        UUID subjectId = row.subjectId();
        if ("SUSPENDED".equals(bucket) && autoSuspend) {
            return "GROUP".equals(subjectType)
                    ? userClient.suspendGroup(subjectId, null, "ARREARS_ESCALATION")
                    : userClient.suspendMember(subjectId, null, "ARREARS_ESCALATION");
        }
        if ("WRITE_OFF".equals(bucket) && autoWriteOff) {
            return "GROUP".equals(subjectType)
                    ? userClient.deactivateGroup(subjectId, null, "ARREARS_ESCALATION")
                    : userClient.deactivateMember(subjectId, null, "ARREARS_ESCALATION");
        }
        return Mono.empty();
    }
}
