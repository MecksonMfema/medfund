package com.medfund.shared.report;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 17 §A.3 — the wire shape finance-service uses to invoke an owner
 * service's {@code POST /api/v1/reports/{reportKey}/scheduled-render}
 * endpoint. Each owner service accepts the same envelope and pulls
 * whichever fields its shape service needs.
 *
 * <p>{@code actorId} and {@code actorEmail} carry the human who last
 * touched the schedule row — the owner service uses them for the
 * {@code SecurityEventPublisher.publishDataAccess(...)} audit trail so a
 * scheduled fire is attributable to a real person even though the HTTP
 * call itself carries a service-to-service token.
 *
 * <p>Phase 19 §B Phase 12 added {@code params} — an opaque per-key JSON
 * object plumbed through from {@code tenant_report_schedule.params}. Owner
 * services that don't need per-schedule tunables ignore the field; today
 * only {@code FRAUD_SIU_REPORT} reads it ({@code includeSensitiveSheets}
 * per FR12). Nullable — the legacy constructor delegates to {@code null}
 * so pre-Phase-12 callers keep compiling.
 */
public record ScheduledRenderRequest(
        UUID tenantId,
        LocalDate periodStart,
        LocalDate periodEnd,
        LocalDate asOf,
        String reportingCurrency,
        String cadenceLabel,
        UUID scheduleId,
        UUID actorId,
        String actorEmail,
        Map<String, Object> params
) {
    public ScheduledRenderRequest(
            UUID tenantId,
            LocalDate periodStart,
            LocalDate periodEnd,
            LocalDate asOf,
            String reportingCurrency,
            String cadenceLabel,
            UUID scheduleId,
            UUID actorId,
            String actorEmail) {
        this(tenantId, periodStart, periodEnd, asOf, reportingCurrency,
                cadenceLabel, scheduleId, actorId, actorEmail, null);
    }
}
