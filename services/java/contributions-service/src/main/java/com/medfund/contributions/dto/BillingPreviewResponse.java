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
    /**
     * One row per insured PERSON the run would bill. The member's own line
     * has {@code dependantId == null} and {@code personType = "MEMBER"};
     * a dependant's line has both the parent {@code memberId} (for invoice
     * grouping) AND its own {@code dependantId}, with
     * {@code personType = "DEPENDANT"}.
     */
    public record SampleRow(
            UUID memberId,
            UUID dependantId,
            String memberNumber,
            /** Display name — member's name on the member's line, dependant's name on the dependant's line. */
            String personName,
            /** "MEMBER" or "DEPENDANT". */
            String personType,
            UUID schemeId,
            String schemeName,
            UUID groupId,
            /** Friendly band label (e.g. "Adult", "Senior") — null when no band matched. */
            String ageBand,
            BigDecimal amount,
            String currencyCode
    ) {}
}
