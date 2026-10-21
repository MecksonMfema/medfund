package com.medfund.finance.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Phase 5 member-payments unified report payload. One row per
 * (member, currency) combining the three legs that touch a member's
 * money: what was billed, what was received (net, per the F25 sign
 * convention), and what claims actually paid out. {@code netPosition}
 * = received − claimsPaid shows the member's open balance direction.
 */
public record MemberPaymentsReportResponse(
        LocalDate periodStart,
        LocalDate periodEnd,
        List<MemberPaymentRow> rows
) {

    public record MemberPaymentRow(
            UUID memberId,
            String memberName,
            String currencyCode,
            BigDecimal totalBilled,
            BigDecimal totalReceived,
            BigDecimal totalClaimsPaid,
            BigDecimal netPosition
    ) {}
}
