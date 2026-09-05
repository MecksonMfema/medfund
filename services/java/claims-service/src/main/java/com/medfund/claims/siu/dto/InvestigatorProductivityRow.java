package com.medfund.claims.siu.dto;

import java.math.BigDecimal;

/**
 * Investigator productivity row for the
 * {@code /reports/fraud/investigator-productivity} endpoint (§B Phase 11).
 * Role-gated on the backend per Rule 4: {@code siu_officer} sees only
 * their own row; {@code siu_supervisor} + {@code tenant_admin} see all.
 */
public record InvestigatorProductivityRow(
        String officerEmail,
        long casesClosed,
        long confirmedCount,
        long dismissedCount,
        long referredCount,
        BigDecimal avgCycleTimeDays
) {
}
