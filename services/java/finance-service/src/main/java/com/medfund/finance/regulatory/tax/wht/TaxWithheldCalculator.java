package com.medfund.finance.regulatory.tax.wht;

import com.medfund.shared.report.regulatory.RegulatoryReportGenerationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Derives per-category and total withholding-tax amounts for a
 * ({@code tenantId}, period) return. Applies the per-item pre-computed
 * WHT captured on the payment run when non-zero (bespoke vendor rate
 * or negotiated exemption), else falls back to the tenant-default rate
 * from {@link TaxWithheldRateReader} × the category base.
 *
 * <p>Amounts are rounded HALF_UP to 2 decimal places.
 */
@Slf4j
@Component
public class TaxWithheldCalculator {

    public record Computed(
            BigDecimal commissionBase,
            BigDecimal commissionWht,
            BigDecimal professionalFeesBase,
            BigDecimal professionalFeesWht,
            BigDecimal dividendsBase,
            BigDecimal dividendsWht,
            BigDecimal otherBase,
            BigDecimal otherWht,
            BigDecimal totalPaymentsBase,
            BigDecimal totalWithholdingPayable) {}

    public Computed compute(TaxWithheldRawData raw, TaxWithheldRates rates) {
        if (raw == null) {
            throw new RegulatoryReportGenerationException("raw data required");
        }
        if (rates == null) {
            throw new RegulatoryReportGenerationException("rates required");
        }
        BigDecimal commissionWht  = resolveWht(raw.commissionBase(), raw.commissionOverrideWht(), rates.commissionRate());
        BigDecimal profFeesWht    = resolveWht(raw.professionalFeesBase(), raw.professionalFeesOverrideWht(), rates.professionalFeesRate());
        BigDecimal dividendsWht   = resolveWht(raw.dividendsBase(), raw.dividendsOverrideWht(), rates.dividendsRate());
        BigDecimal otherWht       = resolveWht(raw.otherBase(), raw.otherOverrideWht(), rates.otherRate());
        BigDecimal totalBase      = round(raw.commissionBase().add(raw.professionalFeesBase())
                                            .add(raw.dividendsBase()).add(raw.otherBase()));
        BigDecimal totalPayable   = round(commissionWht.add(profFeesWht).add(dividendsWht).add(otherWht));
        return new Computed(
                round(raw.commissionBase()),        commissionWht,
                round(raw.professionalFeesBase()),  profFeesWht,
                round(raw.dividendsBase()),         dividendsWht,
                round(raw.otherBase()),             otherWht,
                totalBase,                          totalPayable);
    }

    /**
     * Per-line fallback: use the pre-computed WHT captured on the payment
     * run when non-zero (bespoke rate, treaty override, or manual
     * correction); else apply the tenant-default rate × base.
     */
    private static BigDecimal resolveWht(BigDecimal base, BigDecimal overrideWht, BigDecimal defaultRate) {
        if (overrideWht != null && overrideWht.signum() != 0) {
            return round(overrideWht);
        }
        BigDecimal rate = defaultRate != null ? defaultRate : BigDecimal.ZERO;
        return round(base.multiply(rate));
    }

    private static BigDecimal round(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}
