package com.medfund.contributions.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Full statement payload — header summary + chronological ledger lines.
 * The header carries everything the page needs to render a printable
 * top-of-statement card: who, what period, in which currency, and the
 * opening / closing balances bracketing the period.
 */
public record StatementResponse(
        Header header,
        List<StatementLine> lines
) {
    public record Header(
            String targetType,           // GROUP | MEMBER
            UUID targetId,
            String targetName,
            String targetCode,           // member_number or registration_number
            LocalDate periodStart,
            LocalDate periodEnd,
            String currencyCode,
            BigDecimal openingBalance,
            BigDecimal closingBalance,
            BigDecimal totalCharges,
            BigDecimal totalPayments
    ) {}
}
