package com.medfund.rules.model;

import com.medfund.rules.fact.SchemeChangeContext;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Seven baked-in strategies for prorating a member's benefit limit after a
 * mid-year scheme change. Selected per-tenant (via {@code tenant_proration_config})
 * or per-condition (via a BENEFIT_PRORATION rule).
 *
 * <p>Each strategy computes an {@code effectiveLimit} in the new scheme benefit's
 * currency. The claim's remaining balance is then {@code effectiveLimit - totalConsumed},
 * floored at zero.
 *
 * <p>Cross-currency scheme changes short-circuit to {@link #NONE} inside
 * {@code ProrationService} — the arithmetic here assumes both schemes share a
 * currency.
 */
public enum ProrationStrategy {

    /** No proration — use the new scheme's raw annual limit. Preserves pre-feature behaviour. */
    NONE {
        @Override
        public BigDecimal effectiveLimit(SchemeChangeContext ctx, BigDecimal newAnnualLimit) {
            return newAnnualLimit;
        }
    },

    /** {@code newLimit − consumedAcrossAnyScheme}, floored at 0. Simplest safe correction. */
    DELTA_CREDIT {
        @Override
        public BigDecimal effectiveLimit(SchemeChangeContext ctx, BigDecimal newAnnualLimit) {
            BigDecimal consumed = ctx.totalConsumed();
            return max(newAnnualLimit.subtract(consumed), BigDecimal.ZERO);
        }
    },

    /** MASCA-style spending-ratio transfer: {@code (oldRemaining / oldLimit) × newLimit}. */
    RATIO_CARRY {
        @Override
        public BigDecimal effectiveLimit(SchemeChangeContext ctx, BigDecimal newAnnualLimit) {
            BigDecimal oldLimit = ctx.getPrevAnnualLimit();
            if (oldLimit == null || oldLimit.signum() <= 0) {
                return newAnnualLimit;
            }
            BigDecimal oldRemaining = max(oldLimit.subtract(nz(ctx.getConsumedUnderPrevScheme())), BigDecimal.ZERO);
            BigDecimal ratio = oldRemaining.divide(oldLimit, 6, RoundingMode.HALF_UP);
            BigDecimal effective = newAnnualLimit.multiply(ratio);
            // The rider's already-billed consumption under the NEW scheme still counts:
            return max(effective.subtract(nz(ctx.getConsumedUnderNewScheme())), BigDecimal.ZERO);
        }
    },

    /** {@code newLimit × (daysRemainingInYear / daysInYear) − consumedUnderNewScheme}, floored at 0. */
    CALENDAR {
        @Override
        public BigDecimal effectiveLimit(SchemeChangeContext ctx, BigDecimal newAnnualLimit) {
            int days = ctx.getDaysInYear() > 0 ? ctx.getDaysInYear() : 365;
            BigDecimal fraction = BigDecimal.valueOf(ctx.getDaysRemainingInYear())
                    .divide(BigDecimal.valueOf(days), 6, RoundingMode.HALF_UP);
            BigDecimal effective = newAnnualLimit.multiply(fraction);
            return max(effective.subtract(nz(ctx.getConsumedUnderNewScheme())), BigDecimal.ZERO);
        }
    },

    /**
     * Two budgets on either side of the effective date. This method returns the
     * NEW-scheme budget's remaining balance; ProrationService is responsible for
     * ensuring the OLD-scheme budget was already checked for claims dated before
     * {@code effectiveDate}. Standard shape:
     * {@code newLimit × (daysAfter / daysInYear) − consumedUnderNewScheme}.
     */
    SPLIT_YEAR {
        @Override
        public BigDecimal effectiveLimit(SchemeChangeContext ctx, BigDecimal newAnnualLimit) {
            int days = ctx.getDaysInYear() > 0 ? ctx.getDaysInYear() : 365;
            BigDecimal fraction = BigDecimal.valueOf(ctx.getDaysRemainingInYear())
                    .divide(BigDecimal.valueOf(days), 6, RoundingMode.HALF_UP);
            BigDecimal effective = newAnnualLimit.multiply(fraction);
            return max(effective.subtract(nz(ctx.getConsumedUnderNewScheme())), BigDecimal.ZERO);
        }
    },

    /**
     * The <em>increment</em> ({@code newLimit − oldLimit}) unlocks only after
     * {@code ProrationService} confirms {@code daysSinceChange >= incrementWaitDays}.
     * Until then, the effective limit is capped at {@code oldLimit − totalConsumed}.
     * Downgrades (negative increment) fall through to DELTA_CREDIT.
     */
    WAITING_PERIOD_ON_INCREMENT {
        @Override
        public BigDecimal effectiveLimit(SchemeChangeContext ctx, BigDecimal newAnnualLimit) {
            BigDecimal oldLimit = nz(ctx.getPrevAnnualLimit());
            BigDecimal delta = newAnnualLimit.subtract(oldLimit);
            if (delta.signum() <= 0) {
                // Downgrade or same limit — increment isn't a thing here.
                return max(newAnnualLimit.subtract(ctx.totalConsumed()), BigDecimal.ZERO);
            }
            // Caller decides whether the increment is unlocked and picks the
            // active limit accordingly; the enum offers the pre-unlock ceiling
            // as its "effective limit" when consulted with the OLD ceiling.
            return max(oldLimit.subtract(ctx.totalConsumed()), BigDecimal.ZERO);
        }
    },

    /**
     * Placeholder for {@code HYBRID_BY_DIRECTION} — the real routing happens in
     * {@code ProrationService}, which reads
     * {@code tenant_proration_config.upgrade_strategy / downgrade_strategy /
     * currency_strategy} and dispatches to one of the other strategies. If a
     * caller asks HYBRID to compute directly (shouldn't happen), it degrades to
     * NONE so nothing rejects unexpectedly.
     */
    HYBRID_BY_DIRECTION {
        @Override
        public BigDecimal effectiveLimit(SchemeChangeContext ctx, BigDecimal newAnnualLimit) {
            return newAnnualLimit;
        }
    };

    public abstract BigDecimal effectiveLimit(SchemeChangeContext ctx, BigDecimal newAnnualLimit);

    private static BigDecimal max(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) >= 0 ? a : b;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
