package com.medfund.contributions.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

public record BillingCommitResponse(
        long contributionsCreated,
        Map<String, BigDecimal> totalsByCurrency,
        Instant committedAt,
        /** Aggregate invoices addressed to a group's liaison. */
        long groupInvoicesCreated,
        /** Per-member invoices (INDIVIDUAL_ONLY tenants, or ungrouped members in BOTH). */
        long individualInvoicesCreated,
        /** Which membership model the tenant was on when this commit ran. */
        String membershipModel
) {}
