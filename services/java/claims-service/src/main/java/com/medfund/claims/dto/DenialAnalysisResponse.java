package com.medfund.claims.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * DENIAL_ANALYSIS composite payload (G47) — four views over the REJECTED
 * claim set of the window. Primary aggregation column is
 * {@code claimed_amount} per G42/G47 (approved is 0 by definition of
 * REJECTED). {@code monthlyTrend} is only populated when the period spans
 * more than one calendar month (the service gates it).
 *
 * <p>All amounts are native-currency aggregates per G25. Provider denial
 * rate is a share ratio (denied/total) and is always safe from FX
 * conversion.
 */
public record DenialAnalysisResponse(
        List<CategoryRow> byCategory,
        List<CodeRow>     byCode,
        List<ProviderRow> byProvider,
        List<MonthlyRow>  monthlyTrend
) {
    public record CategoryRow(String category, long claimCount, BigDecimal totalClaimed) {}

    public record CodeRow(String code, String category, String description,
                          long claimCount, BigDecimal totalClaimed) {}

    public record ProviderRow(UUID providerId, String providerName,
                              long claimCount, BigDecimal totalClaimed,
                              BigDecimal denialRatePct) {}

    public record MonthlyRow(LocalDate month, long claimCount, BigDecimal totalClaimed) {}
}
