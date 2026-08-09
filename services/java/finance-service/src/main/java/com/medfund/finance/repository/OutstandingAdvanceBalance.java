package com.medfund.finance.repository;

import java.math.BigDecimal;

/**
 * Per-currency outstanding advance balance for a single payee. Emitted by
 * {@link AdvancePaymentRepository#findOutstandingByProvider(java.util.UUID)}
 * and its member sibling. One row per (payee, currency) with a positive
 * balance — zero / negative balances are filtered out by the query.
 */
public record OutstandingAdvanceBalance(String currencyCode, BigDecimal outstanding) {}
