package com.medfund.shared.report;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Kafka wire schema for {@code medfund.aml.suspicious-transaction}
 * (Phase 22 REG8 + Phase 24). Published by finance-service's
 * {@code AmlAlertService} on every RAISE / REVIEW / FILE / CLOSE
 * transition; consumed by notification-service's
 * {@code internal/aml/dispatcher.go} which currently logs (stub) and
 * carries a hook slot for the future fraud-detector AI integration.
 *
 * <p>Deploy-order invariant per F-REG7: the Go consumer must be live
 * before the Java producer begins publishing — otherwise events land
 * against an empty consumer group and are only picked up when the
 * dispatcher rolls, arriving late.
 *
 * <p>{@code schemaVersion} bumps only on breaking changes; adding a
 * field keeps the version.
 *
 * <p>Rule-8 audit is orthogonal — the workflow already emits an
 * {@code AuditEvent} on every transition; this event is a
 * fan-out signal for downstream consumers (dispatch, fraud AI, dashboard
 * tickers), not an audit-log replacement.
 */
public record SuspiciousTransactionEvent(
        int schemaVersion,
        UUID tenantId,
        UUID alertId,
        String transactionRef,
        String transactionType,
        BigDecimal amountNative,
        String currency,
        UUID memberId,
        UUID providerId,
        String priorStatus,       // Null on RAISE; the from-state on transitions.
        String newStatus,         // RAISED | REVIEWED | FILED | CLOSED
        String transition,        // RAISE | REVIEW | FILE | CLOSE
        String actorId,
        String actorEmail,
        Instant occurredAt) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public static final String TOPIC = "medfund.aml.suspicious-transaction";

    public static final String TRANSITION_RAISE  = "RAISE";
    public static final String TRANSITION_REVIEW = "REVIEW";
    public static final String TRANSITION_FILE   = "FILE";
    public static final String TRANSITION_CLOSE  = "CLOSE";
}
