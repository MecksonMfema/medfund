package com.medfund.contributions.premium.service;

import com.medfund.contributions.premium.entity.EarningSchedule;
import com.medfund.rules.fact.PremiumFact;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Splits a policy's written premium into per-period earning strip rows for
 * three tenant-configurable methods (Phase 12 §A U11, grill note 4):
 * <ul>
 *   <li>{@code DAILY_LINEAR} — days-in-period ÷ days-in-cover × written.</li>
 *   <li>{@code MONTHLY_24THS} — half-month-of-coverage; IPEC-mandated for
 *       motor. Every month gets 2/24ths except the first and last which
 *       get 1/24th each (mid-month convention).</li>
 *   <li>{@code LINEAR_WITH_LOADING} — front-load a configured percent into
 *       the first period; spread the remainder linearly across the rest.</li>
 * </ul>
 *
 * <p>Rows are minted at grain "one per calendar month spanned by the
 * coverage window", with the first and last months pro-rated on days. Sum
 * of {@code written_amount} across the strip equals {@code writtenPremium}
 * modulo rounding — the final period absorbs the rounding remainder so the
 * strip is exact to the paise.
 */
@Component
public class PremiumEarningStripCalculator {

    private static final int SCALE = 4;
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal TWO = new BigDecimal("2");

    public List<EarningSchedule> split(PremiumFact fact) {
        LocalDate coverageStart = fact.getCoverageStart();
        LocalDate coverageEnd = fact.getCoverageEnd();
        BigDecimal writtenPremium = fact.getWrittenPremium();
        String method = fact.getEarningMethod() != null ? fact.getEarningMethod() : "DAILY_LINEAR";

        if (coverageStart == null || coverageEnd == null || writtenPremium == null) {
            return List.of();
        }
        if (coverageEnd.isBefore(coverageStart)) {
            return List.of();
        }

        List<LocalDate[]> periods = enumerateMonthlyPeriods(coverageStart, coverageEnd);
        if (periods.isEmpty()) {
            return List.of();
        }

        List<BigDecimal> amounts = switch (method) {
            case "MONTHLY_24THS" -> monthly24thsShares(writtenPremium, periods.size());
            case "LINEAR_WITH_LOADING" -> loadingShares(writtenPremium, periods,
                    fact.getLoadingPercent() != null ? fact.getLoadingPercent() : BigDecimal.ZERO);
            default -> dailyLinearShares(writtenPremium, coverageStart, coverageEnd, periods);
        };

        // Absorb rounding drift in the final row so the sum matches
        // writtenPremium exactly.
        BigDecimal running = BigDecimal.ZERO;
        for (int i = 0; i < amounts.size() - 1; i++) running = running.add(amounts.get(i));
        amounts.set(amounts.size() - 1, writtenPremium.subtract(running).setScale(SCALE, RoundingMode.HALF_EVEN));

        List<EarningSchedule> rows = new ArrayList<>(periods.size());
        for (int i = 0; i < periods.size(); i++) {
            EarningSchedule row = new EarningSchedule();
            row.setPolicyId(parseUuid(fact.getPolicyId()));
            row.setPolicySource(fact.getPolicySource());
            row.setInsuranceLine(fact.getInsuranceLine());
            row.setPeriodStart(periods.get(i)[0]);
            row.setPeriodEnd(periods.get(i)[1]);
            row.setWrittenAmount(amounts.get(i));
            row.setCurrencyCode(fact.getCurrencyCode());
            row.setEarningMethod(method);
            row.setPortfolioId(parseUuid(fact.getPortfolioId()));
            row.setCohortId(parseUuid(fact.getCohortId()));
            rows.add(row);
        }
        return rows;
    }

    private static List<BigDecimal> dailyLinearShares(BigDecimal written, LocalDate coverageStart,
                                                       LocalDate coverageEnd, List<LocalDate[]> periods) {
        long totalDays = ChronoUnit.DAYS.between(coverageStart, coverageEnd) + 1;
        BigDecimal totalDaysBd = BigDecimal.valueOf(totalDays);
        List<BigDecimal> out = new ArrayList<>(periods.size());
        for (LocalDate[] p : periods) {
            long periodDays = ChronoUnit.DAYS.between(p[0], p[1]) + 1;
            BigDecimal share = written.multiply(BigDecimal.valueOf(periodDays))
                    .divide(totalDaysBd, SCALE, RoundingMode.HALF_EVEN);
            out.add(share);
        }
        return out;
    }

    private static List<BigDecimal> monthly24thsShares(BigDecimal written, int months) {
        // 24ths convention (IPEC ZW motor): first + last month each earn a
        // half share (1/24 of a 12-month policy), every intermediate month
        // earns a full share (2/24). Rescaled to arbitrary N-month strips
        // by normalising the denominator to (2N - 2) so the total sums to
        // `written` (last-row absorption handles rounding drift).
        List<BigDecimal> out = new ArrayList<>(months);
        if (months == 1) {
            out.add(written.setScale(SCALE, RoundingMode.HALF_EVEN));
            return out;
        }
        BigDecimal denominator = new BigDecimal(2 * months - 2);
        BigDecimal fullShare = written.multiply(TWO).divide(denominator, SCALE, RoundingMode.HALF_EVEN);
        BigDecimal halfShare = written.divide(denominator, SCALE, RoundingMode.HALF_EVEN);
        out.add(halfShare);
        for (int i = 1; i < months - 1; i++) out.add(fullShare);
        out.add(halfShare);
        return out;
    }

    private static List<BigDecimal> loadingShares(BigDecimal written, List<LocalDate[]> periods,
                                                   BigDecimal loadingPercent) {
        // The first period earns (loadingPercent / 100) × written on top of
        // its day-share; the rest of the premium is spread linearly across
        // all periods on a days-in-period basis. Loading percent is clamped
        // to [0, 100] to keep the base share non-negative.
        BigDecimal loading = loadingPercent == null ? BigDecimal.ZERO : loadingPercent;
        if (loading.signum() < 0) loading = BigDecimal.ZERO;
        if (loading.compareTo(HUNDRED) > 0) loading = HUNDRED;

        BigDecimal frontLoaded = written.multiply(loading).divide(HUNDRED, SCALE, RoundingMode.HALF_EVEN);
        BigDecimal remaining = written.subtract(frontLoaded);

        LocalDate coverageStart = periods.get(0)[0];
        LocalDate coverageEnd = periods.get(periods.size() - 1)[1];
        List<BigDecimal> linear = dailyLinearShares(remaining, coverageStart, coverageEnd, periods);
        // Add the loading onto the first period only.
        linear.set(0, linear.get(0).add(frontLoaded).setScale(SCALE, RoundingMode.HALF_EVEN));
        return linear;
    }

    /**
     * Enumerate the monthly periods spanned by {@code [coverageStart, coverageEnd]}
     * inclusive. Each returned {@code LocalDate[]{start, end}} is clipped to the
     * coverage window at both ends — the first month starts on
     * {@code coverageStart}, the last ends on {@code coverageEnd}.
     */
    static List<LocalDate[]> enumerateMonthlyPeriods(LocalDate coverageStart, LocalDate coverageEnd) {
        List<LocalDate[]> out = new ArrayList<>();
        LocalDate cursor = coverageStart.withDayOfMonth(1);
        while (!cursor.isAfter(coverageEnd)) {
            LocalDate periodStart = cursor.isBefore(coverageStart) ? coverageStart : cursor;
            LocalDate lastOfMonth = cursor.withDayOfMonth(cursor.lengthOfMonth());
            LocalDate periodEnd = lastOfMonth.isAfter(coverageEnd) ? coverageEnd : lastOfMonth;
            out.add(new LocalDate[]{periodStart, periodEnd});
            cursor = cursor.plusMonths(1);
        }
        return out;
    }

    private static java.util.UUID parseUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try { return java.util.UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
    }
}
