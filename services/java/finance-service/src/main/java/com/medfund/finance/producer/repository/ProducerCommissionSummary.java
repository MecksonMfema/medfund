package com.medfund.finance.producer.repository;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One row of {@code commission_transaction} aggregated for payout — the
 * grand total ACCRUED amount owed to {@code producerId} within a period,
 * expressed in the underlying contribution's currency
 * ({@code nativeCurrency}). Multiple rows land per producer when they've
 * accrued commissions in more than one native currency; the payout
 * generator sums them in the producer's {@code homeCurrency} at commit
 * time per {@code .claude/multi-currency.md:164}.
 */
public record ProducerCommissionSummary(
        UUID producerId,
        String homeCurrency,
        BigDecimal totalNative,
        String nativeCurrency,
        long txnCount
) {}
