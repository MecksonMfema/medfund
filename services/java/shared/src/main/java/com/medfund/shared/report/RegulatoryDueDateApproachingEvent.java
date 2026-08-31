package com.medfund.shared.report;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Kafka wire schema for {@code medfund.regulatory.due-date-approaching}
 * (Phase 16 §0 REG20). Published by finance-service's
 * {@code RegulatoryDueDateScanner} cron on the four tiers
 * {@code DUE_DATE_7D}, {@code DUE_DATE_1D}, {@code DUE_DATE_0D},
 * {@code DUE_DATE_OVERDUE}; consumed by notification-service's
 * {@code internal/regulatory/dispatcher.go} which fans out email to
 * subscribed recipients on {@code public.tenant_regulatory_recipient}.
 *
 * <p>Deploy-order invariant per F-REG7: the Go consumer must be live
 * before the Java scanner cron begins publishing — otherwise events land
 * against an empty consumer group and are picked up only when the
 * dispatcher rolls, arriving late.
 *
 * <p>{@code schemaVersion} bumps only on breaking changes; adding a
 * field keeps the version.
 */
public record RegulatoryDueDateApproachingEvent(
        int schemaVersion,
        UUID tenantId,
        String reportKey,
        LocalDate periodStart,
        LocalDate periodEnd,
        LocalDate dueDate,
        long daysUntilDue,
        String severity,     // INFO / AMBER / RED
        String eventTier,    // DUE_DATE_7D / DUE_DATE_1D / DUE_DATE_0D / DUE_DATE_OVERDUE
        Instant occurredAt) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public static final String TOPIC = "medfund.regulatory.due-date-approaching";
}
