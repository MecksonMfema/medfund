package com.medfund.shared.report;

import java.time.LocalDate;
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
        String actorEmail
) {}
