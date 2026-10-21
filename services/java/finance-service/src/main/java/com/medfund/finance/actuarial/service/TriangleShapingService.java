package com.medfund.finance.actuarial.service;

import com.medfund.finance.client.ClaimsClient;
import com.medfund.finance.client.ClaimsClient.AdjudicatedClaimRow;
import com.medfund.finance.client.FxConverter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Shapes a period-scoped stream of adjudicated claims into the wide-matrix
 * {@code triangle} payload the ai-service compute layer consumes. Handles the
 * three A3 shapes:
 * <ul>
 *   <li><b>paid</b> — development axis is {@code submission_date} as the
 *       paid-date proxy (A3), cell value is {@code approved_amount}.</li>
 *   <li><b>incurred</b> — development axis is also
 *       {@code submission_date} (dev delta from {@code service_date}),
 *       cell value is {@code approved_amount}. Distinguishes from paid on the
 *       compute side once {@code reserved_amount} history joins in (V139).</li>
 *   <li><b>reported</b> — same axes, cell value is {@code claimed_amount}
 *       proxied by {@code approved_amount} (both live on the projection today;
 *       distinct wire treatment lands with the paid_amount plumbing).</li>
 * </ul>
 *
 * <p>Missing FX for a given (currency, service_date) skips the claim + appends
 * a warning per Grill note 4 — never fails the whole shape.
 *
 * <p>Output is a wide cumulative triangle where cell[i][j] is the sum-to-date
 * for accident period {@code i} at development period {@code j}. Upper-right
 * cells that lie in the future (accident + dev > periodEnd) are {@code null}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TriangleShapingService {

    public enum Shape { paid, incurred, reported }

    public enum Grain {
        month, quarter, year;

        int monthsPerBucket() {
            return switch (this) {
                case month -> 1;
                case quarter -> 3;
                case year -> 12;
            };
        }

        String format(YearMonth ym) {
            return switch (this) {
                case month -> String.format("%04d-%02d", ym.getYear(), ym.getMonthValue());
                case quarter -> String.format("%04dQ%d", ym.getYear(), ((ym.getMonthValue() - 1) / 3) + 1);
                case year -> String.format("%04d", ym.getYear());
            };
        }
    }

    private final ClaimsClient claimsClient;
    private final FxConverter fxConverter;

    public Mono<TriangleShapeResult> shape(TriangleShapeRequest request) {
        List<String> warnings = new ArrayList<>();
        return claimsClient.streamAdjudicatedClaimsForBackfill(
                        request.insuranceLine(), request.periodStart(), 100)
                .filter(row -> serviceDateInPeriod(row, request))
                .filter(row -> row.approvedAmount() != null && row.approvedAmount().signum() > 0)
                .concatMap(row -> convertRowToReportingCurrency(row, request, warnings))
                .collectList()
                .map(rows -> bucketIntoTriangle(rows, request, warnings));
    }

    private boolean serviceDateInPeriod(AdjudicatedClaimRow row, TriangleShapeRequest request) {
        LocalDate serviceDate = row.serviceDate();
        if (serviceDate == null) return false;
        return !serviceDate.isBefore(request.periodStart())
                && !serviceDate.isAfter(request.periodEnd());
    }

    private Mono<ConvertedClaim> convertRowToReportingCurrency(AdjudicatedClaimRow row,
                                                               TriangleShapeRequest request,
                                                               List<String> warnings) {
        LocalDate serviceDate = row.serviceDate();
        String fromCurrency = row.currencyCode() != null ? row.currencyCode() : request.reportingCurrency();
        return fxConverter.convert(row.approvedAmount(), fromCurrency,
                        request.reportingCurrency(), serviceDate, request.tenantId())
                .map(convertedAmount -> new ConvertedClaim(
                        serviceDate,
                        row.submissionDate() != null
                                ? LocalDate.ofInstant(row.submissionDate(), ZoneOffset.UTC)
                                : serviceDate,
                        convertedAmount))
                .onErrorResume(e -> {
                    warnings.add("Skipping claim " + row.claimId() + " - FX rate missing for "
                            + fromCurrency + "->" + request.reportingCurrency() + " on " + serviceDate);
                    return Mono.empty();
                });
    }

    private TriangleShapeResult bucketIntoTriangle(List<ConvertedClaim> claims,
                                                   TriangleShapeRequest request,
                                                   List<String> warnings) {
        Grain grain = request.grain();
        int monthsPerBucket = grain.monthsPerBucket();

        YearMonth firstPeriod = alignToGrainStart(YearMonth.from(request.periodStart()), grain);
        YearMonth lastPeriod = alignToGrainStart(YearMonth.from(request.periodEnd()), grain);
        int accidentPeriodCount = periodsBetween(firstPeriod, lastPeriod, monthsPerBucket) + 1;

        List<String> accidentPeriods = new ArrayList<>(accidentPeriodCount);
        for (int i = 0; i < accidentPeriodCount; i++) {
            accidentPeriods.add(grain.format(firstPeriod.plusMonths((long) i * monthsPerBucket)));
        }
        List<String> developmentPeriods = new ArrayList<>(accidentPeriodCount);
        for (int i = 0; i < accidentPeriodCount; i++) {
            developmentPeriods.add(String.valueOf(i + 1));
        }

        BigDecimal[][] cells = new BigDecimal[accidentPeriodCount][accidentPeriodCount];
        for (ConvertedClaim claim : claims) {
            YearMonth accident = alignToGrainStart(YearMonth.from(claim.accidentDate()), grain);
            YearMonth development = alignToGrainStart(YearMonth.from(claim.developmentDate()), grain);
            int accidentIdx = periodsBetween(firstPeriod, accident, monthsPerBucket);
            int devIdx = periodsBetween(accident, development, monthsPerBucket);
            if (accidentIdx < 0 || accidentIdx >= accidentPeriodCount) continue;
            if (devIdx < 0 || devIdx >= accidentPeriodCount) continue;
            BigDecimal existing = cells[accidentIdx][devIdx];
            cells[accidentIdx][devIdx] = existing == null
                    ? claim.amount()
                    : existing.add(claim.amount());
        }

        cumulativeInPlace(cells);
        maskFuture(cells, accidentPeriodCount);
        List<List<Object>> serialisableCells = toSerialisable(cells);

        Map<String, Object> triangle = new LinkedHashMap<>();
        triangle.put("accident_periods", accidentPeriods);
        triangle.put("development_periods", developmentPeriods);
        triangle.put("cells", serialisableCells);
        triangle.put("grain", grain.name());
        triangle.put("reporting_currency", request.reportingCurrency());
        triangle.put("insurance_line", request.insuranceLine() != null ? request.insuranceLine() : "ALL");
        triangle.put("shape", request.shape().name());
        return new TriangleShapeResult(triangle, List.copyOf(warnings));
    }

    private static YearMonth alignToGrainStart(YearMonth ym, Grain grain) {
        return switch (grain) {
            case month -> ym;
            case quarter -> YearMonth.of(ym.getYear(), ((ym.getMonthValue() - 1) / 3) * 3 + 1);
            case year -> YearMonth.of(ym.getYear(), 1);
        };
    }

    private static int periodsBetween(YearMonth from, YearMonth to, int monthsPerBucket) {
        int months = (to.getYear() - from.getYear()) * 12 + (to.getMonthValue() - from.getMonthValue());
        return months / monthsPerBucket;
    }

    private static void cumulativeInPlace(BigDecimal[][] cells) {
        int n = cells.length;
        for (int accident = 0; accident < n; accident++) {
            BigDecimal running = BigDecimal.ZERO;
            boolean anySeen = false;
            for (int dev = 0; dev < n; dev++) {
                BigDecimal delta = cells[accident][dev];
                if (delta != null) anySeen = true;
                if (anySeen) {
                    if (delta != null) running = running.add(delta);
                    cells[accident][dev] = running.setScale(2, RoundingMode.HALF_UP);
                }
            }
        }
    }

    private static void maskFuture(BigDecimal[][] cells, int n) {
        for (int accident = 0; accident < n; accident++) {
            int lastValidDev = n - 1 - accident;
            for (int dev = lastValidDev + 1; dev < n; dev++) {
                cells[accident][dev] = null;
            }
        }
    }

    private static List<List<Object>> toSerialisable(BigDecimal[][] cells) {
        List<List<Object>> out = new ArrayList<>(cells.length);
        for (BigDecimal[] row : cells) {
            List<Object> serRow = new ArrayList<>(row.length);
            for (BigDecimal cell : row) {
                serRow.add(cell == null ? null : cell.doubleValue());
            }
            out.add(serRow);
        }
        return out;
    }

    /** Immutable request shape used by {@code ActuarialJobService.submit(...)}. */
    public record TriangleShapeRequest(
            UUID tenantId,
            LocalDate periodStart,
            LocalDate periodEnd,
            String insuranceLine,
            Shape shape,
            Grain grain,
            String reportingCurrency) {

        public TriangleShapeRequest {
            if (periodStart == null || periodEnd == null) {
                throw new IllegalArgumentException("periodStart and periodEnd are required");
            }
            if (periodStart.isAfter(periodEnd)) {
                throw new IllegalArgumentException("periodStart must be <= periodEnd");
            }
            if (shape == null) throw new IllegalArgumentException("shape is required");
            if (grain == null) throw new IllegalArgumentException("grain is required");
            if (reportingCurrency == null || reportingCurrency.isBlank()) {
                throw new IllegalArgumentException("reportingCurrency is required");
            }
        }
    }

    /** Output — the wire-shape triangle map + warnings surfaced up to the caller. */
    public record TriangleShapeResult(Map<String, Object> triangle, List<String> warnings) {}

    private record ConvertedClaim(LocalDate accidentDate, LocalDate developmentDate, BigDecimal amount) {}
}
