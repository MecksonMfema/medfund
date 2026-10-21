package com.medfund.finance.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Phase 8 portfolio-level collection-rate trend. Collapses the
 * per-dimension {@link CollectionRateReportResponse} stacks into one
 * monthly (billed, received, rate) strip per currency — the treasurer's
 * "are we collecting better or worse over time?" view, independent of
 * how the book is split across schemes/groups/members (D8-3).
 *
 * <p>The rate is computed the same way as the dimension report:
 * {@code received / billed * 100}, never cross-currency, {@code null}
 * on a zero denominator.
 */
public record CollectionRateTrendResponse(
        LocalDate periodStart,
        LocalDate periodEnd,
        List<MonthRow> months
) {

    public record MonthRow(
            LocalDate month,
            String currencyCode,
            BigDecimal billed,
            BigDecimal received,
            BigDecimal ratePct
    ) {}
}
