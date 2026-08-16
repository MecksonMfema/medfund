package com.medfund.claims.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * PRE_AUTH_ACTIVITY report payload (Phase 4 §A, G43). Reads
 * {@code pre_authorizations} on the {@code requested_date} clock. The
 * classical utilisation calc is un-computable from stored data (F55 — no
 * claim back-link, no used_amount), so the report surfaces pre-auth
 * activity directly plus a claims-side proxy signal.
 */
public record PreAuthActivityResponse(
        List<PreAuthActivityRow> byStatus,
        R04R05SignalRow r04r05Signal
) {

    /**
     * Proxy-utilisation signal from the claims side: how often claims are
     * rejected because a pre-auth was required-but-missing (R04) or expired
     * (R05) during the same period. A companion metric to the pre-auth
     * activity rows — indicative, not authoritative.
     */
    public record R04R05SignalRow(
            long r04Count,
            long r05Count,
            BigDecimal totalClaimedInR04R05
    ) {
    }
}
