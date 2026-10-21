package com.medfund.claims.costshare;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Read-side DTOs mirroring the four V076 tenant cost-share tables. Claims-service
 * cannot import contributions-service entities, so we snapshot the shapes here
 * and populate them via unqualified R2DBC queries against the tenant search_path.
 */
public final class CostShareConfig {

    private CostShareConfig() {}

    /** Snapshot of {@code scheme_cost_share} for a given (scheme, year, asOf). */
    public record Scheme(
            UUID id,
            UUID schemeId,
            Integer policyYear,
            BigDecimal deductible,
            BigDecimal outOfPocketMax,
            String deductibleScope,      // INDIVIDUAL | FAMILY | EMBEDDED
            String oopScope,
            String shortfallPolicy,      // RECOVER_FROM_MEMBER | ABSORB_BY_FUND
            String currencyCode,
            LocalDate effectiveFrom,
            LocalDate effectiveTo) {
    }

    /** Snapshot of {@code benefit_cost_share} for a given (benefit, asOf). */
    public record Benefit(
            UUID id,
            UUID schemeBenefitId,
            String copayType,            // FLAT | PERCENT | TIERED | null
            BigDecimal copayAmount,
            BigDecimal copayPercentage,
            BigDecimal copayMax,
            BigDecimal coinsuranceRate,
            Boolean appliesToDeductible,
            Boolean appliesToOopMax,
            String basis,
            String currencyCode,          // inherited from the parent benefit (not stored on this row)
            LocalDate effectiveFrom,
            LocalDate effectiveTo) {
    }

    /**
     * Snapshot of {@code member_cost_share_accumulator} for a
     * (member, dependant?, scheme, year) tuple. Under FAMILY scope
     * {@code dependantId == null} — the family pot.
     */
    public record Accumulator(
            UUID id,
            UUID memberId,
            UUID dependantId,
            UUID schemeId,
            Integer policyYear,
            BigDecimal deductibleMet,
            BigDecimal oopMet,
            Integer copayCount,
            String currencyCode,
            Integer version) {

        /** A fresh, zeroed accumulator — used when the caller finds no row for the tuple. */
        public static Accumulator empty(UUID memberId, UUID dependantId, UUID schemeId,
                                        int policyYear, String currencyCode) {
            return new Accumulator(null, memberId, dependantId, schemeId, policyYear,
                    BigDecimal.ZERO, BigDecimal.ZERO, 0, currencyCode, 0);
        }
    }
}
