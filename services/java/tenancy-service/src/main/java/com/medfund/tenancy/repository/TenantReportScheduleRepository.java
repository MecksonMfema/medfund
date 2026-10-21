package com.medfund.tenancy.repository;

import com.medfund.tenancy.entity.TenantReportSchedule;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface TenantReportScheduleRepository
        extends ReactiveCrudRepository<TenantReportSchedule, UUID> {

    Flux<TenantReportSchedule> findByTenantIdOrderByReportKeyAsc(UUID tenantId);

    Mono<TenantReportSchedule> findByTenantIdAndReportKey(UUID tenantId, String reportKey);

    /**
     * Rows currently enabled for the tenant/key. Used by the cascade-disable
     * pass so we can emit a per-schedule audit event with the previous
     * {@code enabled=true} snapshot before the bulk UPDATE flips them.
     */
    Flux<TenantReportSchedule> findByTenantIdAndReportKeyAndEnabledIsTrue(UUID tenantId, String reportKey);

    /**
     * Bulk flip of the enabled flag for a tenant+key. Only affects rows that
     * were previously enabled — the {@code WHERE enabled = TRUE} clause makes
     * this idempotent when called on an already-disabled set (returns 0).
     */
    @Modifying
    @Query("UPDATE public.tenant_report_schedule "
            + "SET enabled = FALSE, updated_at = NOW(), "
            + "    updated_by_actor_id = :actorId, updated_by_actor_email = :actorEmail "
            + "WHERE tenant_id = :tenantId AND report_key = :reportKey AND enabled = TRUE")
    Mono<Integer> cascadeDisable(UUID tenantId, String reportKey, UUID actorId, String actorEmail);

    /** Count of currently-enabled schedules per tenant/key — powers the
     *  {@code activeScheduleCount} field on {@link
     *  com.medfund.tenancy.dto.TenantReportConfigResponse}. */
    @Query("SELECT COUNT(*) FROM public.tenant_report_schedule "
            + "WHERE tenant_id = :tenantId AND report_key = :reportKey AND enabled = TRUE")
    Mono<Long> countActiveByTenantIdAndReportKey(UUID tenantId, String reportKey);
}
