package com.medfund.shared.report;

import com.medfund.shared.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Reader for the platform-wide {@code public.tenant_report_config} table.
 *
 * <p>Every Java service that ships a report endpoint calls
 * {@link #isEnabled(UUID, ReportKey)} either directly or via the
 * {@link RequiresReport} annotation. An absent row = enabled (default
 * TRUE) so a newly-shipped report is visible without a per-tenant
 * migration; the tenant admin has to explicitly disable it to hide it.
 *
 * <p>Uses the {@code public.} prefix per {@code bug_public_prefix_silent_rollback}
 * — the table lives in the platform schema (V130) and every tenant service
 * reads from it directly rather than crossing an HTTP hop to
 * tenancy-service. Same pattern as {@code FxConverter} (reads
 * {@code public.exchange_rates}) and {@code TenantConfigClient}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReportEnablementReader {

    private final DatabaseClient databaseClient;

    /**
     * Resolves tenant from the reactive context (populated by
     * {@code TenantWebFilter}) and short-circuits to enabled when there
     * is no tenant in scope — platform-scope endpoints don't need per-tenant
     * gating.
     */
    public Mono<Boolean> isEnabled(ReportKey reportKey) {
        return Mono.deferContextual(ctx -> {
            String tenantIdStr = TenantContext.get(ctx);
            if (tenantIdStr == null || tenantIdStr.isBlank()) return Mono.just(true);
            UUID tenantId;
            try {
                tenantId = UUID.fromString(tenantIdStr);
            } catch (IllegalArgumentException e) {
                return Mono.just(true);
            }
            return isEnabled(tenantId, reportKey);
        });
    }

    /** Explicit-tenant variant — call from Kafka consumers and scheduled jobs. */
    public Mono<Boolean> isEnabled(UUID tenantId, ReportKey reportKey) {
        if (tenantId == null || reportKey == null) return Mono.just(true);
        return databaseClient.sql("""
                SELECT enabled FROM public.tenant_report_config
                 WHERE tenant_id = :tid AND report_key = :key
                """)
                .bind("tid", tenantId)
                .bind("key", reportKey.name())
                .map((row, meta) -> Boolean.TRUE.equals(row.get("enabled", Boolean.class)))
                .one()
                .defaultIfEmpty(Boolean.TRUE)
                .onErrorResume(err -> {
                    log.warn("[report-config] enabled lookup failed for tenant {} key {}: {} — defaulting to enabled",
                            tenantId, reportKey, err.getMessage());
                    return Mono.just(Boolean.TRUE);
                });
    }

    /**
     * Snapshot of every report the tenant has explicitly toggled OFF. The
     * hub and dynamic sidebar consumers use this to filter their catalogue —
     * inverse of the enabled default so an empty set means "all reports on".
     */
    public Mono<Set<ReportKey>> disabledKeys(UUID tenantId) {
        if (tenantId == null) return Mono.just(Set.of());
        return databaseClient.sql("""
                SELECT report_key FROM public.tenant_report_config
                 WHERE tenant_id = :tid AND enabled = FALSE
                """)
                .bind("tid", tenantId)
                .map((row, meta) -> row.get("report_key", String.class))
                .all()
                .map(ReportKey::parse)
                .filter(java.util.Optional::isPresent)
                .map(java.util.Optional::get)
                .collect(Collectors.toSet())
                .onErrorResume(err -> {
                    log.warn("[report-config] disabled keys lookup failed for tenant {}: {} — assuming none",
                            tenantId, err.getMessage());
                    return Mono.just(Set.of());
                });
    }
}
