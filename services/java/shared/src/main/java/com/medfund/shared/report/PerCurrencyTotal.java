package com.medfund.shared.report;

import java.math.BigDecimal;

/**
 * Native-currency aggregate carried on every {@link ReportResponse#perCurrency()}
 * entry. Fixed shape independent of the report's data type — never a paged
 * sub-slice, always the two ledger-truth numbers a client needs to render a
 * per-currency header strip: the summed amount in that currency and the row
 * count contributing to it.
 *
 * <p>The {@code totalAmount} is always in the currency the map is keyed by —
 * never converted to the reporting currency. Cross-currency conversion for
 * optional client-side display is served by {@link ReportResponse#fxRates()}
 * separately.
 */
public record PerCurrencyTotal(BigDecimal totalAmount, long rowCount) {}
