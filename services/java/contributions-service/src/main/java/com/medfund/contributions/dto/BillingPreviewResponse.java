package com.medfund.contributions.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Wizard step 2 output — what would be created if the wizard committed now.
 *
 * <ul>
 *   <li>{@code totalRows} — number of contribution rows the commit would insert.</li>
 *   <li>{@code totalsByCurrency} — sum of premium per currency_code.</li>
 *   <li>{@code sample} — first up-to-25 rows for the wizard to render before commit.</li>
 *   <li>{@code cooldownActive} — true if the commit endpoint will reject because
 *       another commit ran inside {@code billing_cycle_config.commit_cooldown_hours}.</li>
 * </ul>
 */
public record BillingPreviewResponse(
        long totalRows,
        Map<String, BigDecimal> totalsByCurrency,
        List<SampleRow> sample,
        boolean cooldownActive,
        Integer cooldownRemainingMinutes,
        /** Projected group invoices the commit would create. */
        long groupInvoicesProjected,
        /** Projected individual-member invoices the commit would create. */
        long individualInvoicesProjected,
        /** Tenant's current membership model (INDIVIDUAL_ONLY / GROUP_ONLY / BOTH). */
        String membershipModel
) {
    public record SampleRow(
            UUID memberId,
            String memberNumber,
            UUID schemeId,
            String schemeName,
            UUID groupId,
            BigDecimal amount,
            String currencyCode
    ) {}
}
