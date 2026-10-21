package com.medfund.claims.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One row of the CLAIMS_FREQUENCY_SEVERITY matrix (G48) — a
 * (scheme × insurance-line × currency) cell of annualised claim frequency
 * and severity statistics over the service-date window. Severity percentiles
 * are Postgres-native {@code PERCENTILE_CONT} — server-side aggregate, never
 * a client-side collect-then-sort.
 *
 * <p>{@code exposureMemberMonths} is a proxy (G48 fallback): active-member
 * count × window days ÷ 30.4375, because {@code member_status_history} does
 * not exist yet. The service surfaces a warning on the envelope when the
 * fallback was used.
 */
public record FrequencySeverityRow(
        UUID   schemeId,
        String schemeName,
        String insuranceLine,
        BigDecimal exposureMemberMonths,
        long       claimCount,
        BigDecimal frequency,
        String     currencyCode,
        BigDecimal severityMean,
        BigDecimal severityMedian,
        BigDecimal severityP95
) {
}
