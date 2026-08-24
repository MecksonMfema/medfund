package com.medfund.user.reports.lifecycle.dto;

import java.math.BigDecimal;

/**
 * Phase 13 §C Phase 8 per L10 — one row per (insurance_line, currency_code)
 * pair covering opening / added / renewed / lapsed / terminated / closing
 * counts and the |written_premium| roll for the window.
 *
 * <p>Native currency per parent-plan invariant #1. Cross-currency comparison
 * belongs to the workbook's summary sheet via best-effort FX conversion.
 */
public record PolicyMovementRow(
    String policySource,       // LIFE_POLICY, FUNERAL_POLICY, ...
    String insuranceLine,      // LIFE, FUNERAL, ...
    String currencyCode,
    long   openingCount,
    long   newBusinessCount,
    long   renewedCount,
    long   lapsedCount,
    long   terminatedCount,
    long   closingCount,
    BigDecimal writtenPremiumAdded,
    BigDecimal writtenPremiumRemoved
) {}
