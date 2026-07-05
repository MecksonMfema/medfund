package com.medfund.contributions.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Charge-preview envelope — a projection of what the group or individual
 * would owe in the projected billing cycle if we ran a commit right now.
 * Purely read; no ledger side-effect, no persisted contribution.
 *
 * <p>The projection sits half-way between a contribution statement and a
 * billing-generation preview: statement-shaped in that it's per-subject
 * itemised, but forward-looking rather than historical. Excludes:
 * <ul>
 *   <li>Members whose {@code status} is not {@code active} or {@code suspended}
 *       — deactivated/terminated rows never bill.</li>
 *   <li>Members whose {@code termination_date} lands on or before the
 *       projected {@code periodStart} — they're on their last serviced
 *       cycle and shouldn't be charged for the next one.</li>
 * </ul>
 *
 * <p>Includes future scheme upgrades/downgrades via the {@code
 * billing_age_group_id} + {@code billing_override_effective_from} pair,
 * and per-member custom pricing via {@code billing_override_amount} —
 * both surfaced per line so the operator can see which rows changed.
 *
 * <p>{@code totals} keys by currency because a tenant can have members
 * on different currencies within the same group (multi-currency
 * schemes); the UI renders one row per currency in the footer.
 * {@code asOf} lets the client show "as of 12:04:32" so the operator
 * knows the number is live, not cached.
 */
public record ChargePreviewResponse(
        String subjectType,                  // 'MEMBER' or 'GROUP'
        UUID subjectId,
        String subjectName,
        LocalDate periodStart,
        LocalDate periodEnd,
        List<ChargePreviewLine> lines,
        Map<String, BigDecimal> totals,
        int excludedTerminating,             // count of terminating rows dropped
        Instant asOf
) {}
