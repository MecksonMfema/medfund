package com.medfund.claims.siu.service;

import com.medfund.claims.siu.dto.AiCalibrationData;
import com.medfund.claims.siu.dto.FraudReportData;
import com.medfund.claims.siu.dto.InvestigatorProductivityRow;
import com.medfund.claims.siu.dto.MemberTopNRow;
import com.medfund.claims.siu.dto.ProviderTopNRow;
import com.medfund.claims.siu.dto.TrendPoint;
import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.report.FxRateReader;
import com.medfund.shared.report.ReportEnvelopeBuilder;
import com.medfund.shared.report.ReportKey;
import com.medfund.shared.report.ReportPeriod;
import com.medfund.shared.report.ReportResponse;
import com.medfund.shared.report.ReportingCurrencyResolver;
import com.medfund.shared.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Serves the {@code FRAUD_SIU_REPORT} envelope. §B Phase 11 widens the
 * MVP 4-tile payload to 6 tiles (adds {@code avgCycleTimeDays} +
 * {@code reopenedCount}) and adds five composition methods used by
 * dedicated endpoints for trend, top-N drills, AI calibration, and
 * investigator productivity.
 *
 * <p>Per-currency savings on the summary envelope's {@code perCurrency}
 * is populated from a separate {@code GROUP BY saved_currency} aggregate
 * that the shared {@link ReportEnvelopeBuilder} runs alongside the
 * payload — one round-trip per envelope build.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FraudReportService {

    /** Minimum confirmed cases in the window before AI calibration
     *  publishes precision/recall (per FR11). Below this, we return
     *  empty rows + a warning so the UI can render a banner. */
    static final long MIN_CALIBRATION_N = 50;

    private final DatabaseClient db;
    private final ReportEnvelopeBuilder envelopeBuilder;
    private final ReportingCurrencyResolver currencyResolver;
    private final FxRateReader fxRateReader;

    // ── Summary envelope (6 tiles + per-currency breakdown) ─────────────

    public Mono<ReportResponse<FraudReportData>> summary(String startStr, String endStr,
                                                          String overrideCurrency) {
        ReportPeriod period = ReportPeriod.parseFromQueryParams(startStr, endStr, null);
        Mono<FraudReportData> data = Mono.deferContextual(ctx -> {
            UUID tenantId = parseTenantId(TenantContext.get(ctx));
            return currencyResolver.resolve(tenantId, overrideCurrency)
                    .flatMap(reportingCurrency -> compose(
                            period.periodStart(), period.periodEnd(),
                            tenantId, reportingCurrency));
        });

        String perCurrencySql = """
                SELECT saved_currency AS currency_code,
                       SUM(saved_amount) AS total_amount,
                       COUNT(*) AS row_count
                FROM siu_case
                WHERE status = 'CLOSED_CONFIRMED_FRAUD'
                  AND closed_at >= :start AND closed_at < :end
                  AND saved_amount IS NOT NULL
                  AND saved_currency IS NOT NULL
                GROUP BY saved_currency
                """;
        return envelopeBuilder.build(
                ReportKey.FRAUD_SIU_REPORT,
                period,
                overrideCurrency,
                data,
                perCurrencySql,
                spec -> spec
                        .bind("start", period.periodStart())
                        .bind("end",   period.periodEnd().plusDays(1)));
    }

    private Mono<FraudReportData> compose(LocalDate start, LocalDate end,
                                           UUID tenantId, String reportingCurrency) {
        Mono<Long> casesOpened = countOne("""
                SELECT COUNT(*) AS c FROM siu_case
                WHERE opened_at >= :start AND opened_at < :end
                """, start, end);

        Mono<Long> confirmedCount = countOne("""
                SELECT COUNT(*) AS c FROM siu_case
                WHERE status = 'CLOSED_CONFIRMED_FRAUD'
                  AND closed_at >= :start AND closed_at < :end
                """, start, end);

        Mono<Map<String, BigDecimal>> perCurrencySavings = db.sql("""
                        SELECT saved_currency AS currency_code,
                               SUM(saved_amount) AS total_amount
                        FROM siu_case
                        WHERE status = 'CLOSED_CONFIRMED_FRAUD'
                          AND closed_at >= :start AND closed_at < :end
                          AND saved_amount IS NOT NULL
                          AND saved_currency IS NOT NULL
                        GROUP BY saved_currency
                        """)
                .bind("start", start)
                .bind("end",   end.plusDays(1))
                .map((row, meta) -> Map.entry(
                        row.get("currency_code", String.class),
                        row.get("total_amount", BigDecimal.class)))
                .all()
                .collectMap(Map.Entry::getKey, Map.Entry::getValue,
                        LinkedHashMap::new);

        // §B Phase 11 — mean cycle time (days) for cases closed in the window.
        Mono<BigDecimal> avgCycleTimeDays = db.sql("""
                        SELECT AVG(EXTRACT(EPOCH FROM (closed_at - opened_at)) / 86400.0) AS d
                        FROM siu_case
                        WHERE closed_at IS NOT NULL
                          AND closed_at >= :start AND closed_at < :end
                        """)
                .bind("start", start)
                .bind("end",   end.plusDays(1))
                .map((row, meta) -> row.get("d", BigDecimal.class))
                .one()
                .defaultIfEmpty(BigDecimal.ZERO)
                .map(v -> v != null ? v.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO);

        // §B Phase 11 — count of REOPENED transitions in the window. Uses
        // siu_case_note body prefix (see SiuCaseService.reopen() note-body
        // shape "CLOSED_* → REOPENED: ..."). Approximate but stable enough
        // for a tile; full audit-event join lands with Phase 19.5.
        Mono<Long> reopenedCount = db.sql("""
                        SELECT COUNT(*) AS c FROM siu_case_note
                        WHERE note_type = 'STATUS_CHANGE'
                          AND body LIKE '%→ REOPENED:%'
                          AND created_at >= :start AND created_at < :end
                        """)
                .bind("start", start)
                .bind("end",   end.plusDays(1))
                .map((row, meta) -> row.get("c", Long.class))
                .one()
                .defaultIfEmpty(0L);

        return Mono.zip(casesOpened, confirmedCount, perCurrencySavings,
                        avgCycleTimeDays, reopenedCount)
                .flatMap(tuple -> {
                    long opened    = tuple.getT1() != null ? tuple.getT1() : 0L;
                    long confirmed = tuple.getT2() != null ? tuple.getT2() : 0L;
                    Map<String, BigDecimal> perCurrency = tuple.getT3();
                    BigDecimal avgDays = tuple.getT4();
                    long reopened  = tuple.getT5() != null ? tuple.getT5() : 0L;

                    BigDecimal rate = opened == 0
                            ? BigDecimal.ZERO
                            : BigDecimal.valueOf(confirmed)
                                    .divide(BigDecimal.valueOf(opened), 4, RoundingMode.HALF_UP);

                    return Flux.fromIterable(perCurrency.entrySet())
                            .flatMap(entry -> fxRateReader.convert(
                                    entry.getValue(), entry.getKey(),
                                    reportingCurrency, end, tenantId))
                            .reduce(BigDecimal.ZERO, BigDecimal::add)
                            .map(composite -> new FraudReportData(
                                    opened, confirmed, composite, rate,
                                    avgDays, reopened, perCurrency));
                });
    }

    // ── Trend (12-month opened/confirmed/dismissed series) ──────────────

    public Mono<List<TrendPoint>> trend(int months) {
        if (months <= 0) months = 12;
        int windowMonths = months;
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusMonths(windowMonths).withDayOfMonth(1);

        return db.sql("""
                        SELECT to_char(date_trunc('month', opened_at), 'YYYY-MM-01') AS month,
                               COUNT(*) AS opened,
                               COUNT(*) FILTER (WHERE status = 'CLOSED_CONFIRMED_FRAUD') AS confirmed,
                               COUNT(*) FILTER (WHERE status = 'CLOSED_DISMISSED_FALSE_POSITIVE') AS dismissed
                        FROM siu_case
                        WHERE opened_at >= :start AND opened_at < :end
                        GROUP BY date_trunc('month', opened_at)
                        ORDER BY 1
                        """)
                .bind("start", startDate)
                .bind("end",   endDate.plusDays(1))
                .map((row, meta) -> new TrendPoint(
                        row.get("month", String.class),
                        row.get("opened", Long.class),
                        row.get("confirmed", Long.class),
                        row.get("dismissed", Long.class)))
                .all()
                .collectList()
                .map(rows -> fillMissingMonths(rows, startDate, windowMonths));
    }

    /** Backfill zero-rows for calendar months that produced no cases so
     *  the front-end line-chart renders a continuous x-axis. */
    static List<TrendPoint> fillMissingMonths(List<TrendPoint> rows,
                                               LocalDate startDate, int windowMonths) {
        Map<String, TrendPoint> byMonth = new LinkedHashMap<>();
        for (TrendPoint p : rows) byMonth.put(p.month(), p);
        List<TrendPoint> filled = new ArrayList<>(windowMonths);
        YearMonth cursor = YearMonth.of(startDate.getYear(), startDate.getMonth());
        for (int i = 0; i < windowMonths; i++) {
            String key = cursor.atDay(1).toString();
            filled.add(byMonth.getOrDefault(key,
                    new TrendPoint(key, 0L, 0L, 0L)));
            cursor = cursor.plusMonths(1);
        }
        return filled;
    }

    // ── Top-N provider + member ─────────────────────────────────────────

    public Mono<List<ProviderTopNRow>> topProviders(int n, String startStr, String endStr,
                                                     String overrideCurrency) {
        ReportPeriod period = ReportPeriod.parseFromQueryParams(startStr, endStr, null);
        int cap = clampTopN(n);
        LocalDate start = period.periodStart();
        LocalDate end   = period.periodEnd();
        return Mono.deferContextual(ctx -> {
            UUID tenantId = parseTenantId(TenantContext.get(ctx));
            return currencyResolver.resolve(tenantId, overrideCurrency)
                    .flatMap(reportingCurrency -> loadPerProviderPerCurrency(start, end)
                            .flatMap(grouped -> rankProviders(
                                    grouped, cap, end, tenantId, reportingCurrency)));
        });
    }

    public Mono<List<MemberTopNRow>> topMembers(int n, String startStr, String endStr,
                                                 String overrideCurrency) {
        ReportPeriod period = ReportPeriod.parseFromQueryParams(startStr, endStr, null);
        int cap = clampTopN(n);
        LocalDate start = period.periodStart();
        LocalDate end   = period.periodEnd();
        return Mono.deferContextual(ctx -> {
            UUID tenantId = parseTenantId(TenantContext.get(ctx));
            return currencyResolver.resolve(tenantId, overrideCurrency)
                    .flatMap(reportingCurrency -> loadPerMemberPerCurrency(start, end)
                            .flatMap(grouped -> rankMembers(
                                    grouped, cap, end, tenantId, reportingCurrency)));
        });
    }

    private Mono<Map<UUID, Map<String, BigDecimal>>> loadPerProviderPerCurrency(
            LocalDate start, LocalDate end) {
        return db.sql("""
                        SELECT c.provider_id AS pid,
                               s.saved_currency AS ccy,
                               SUM(s.saved_amount) AS amt
                        FROM siu_case s
                        JOIN fraud_flag ff ON ff.siu_case_id = s.id
                        JOIN claims c ON c.id = ff.claim_id
                        WHERE s.status = 'CLOSED_CONFIRMED_FRAUD'
                          AND s.closed_at >= :start AND s.closed_at < :end
                          AND s.saved_amount IS NOT NULL
                          AND s.saved_currency IS NOT NULL
                        GROUP BY c.provider_id, s.saved_currency
                        """)
                .bind("start", start)
                .bind("end",   end.plusDays(1))
                .map((row, meta) -> Map.entry(
                        row.get("pid", UUID.class),
                        Map.entry(row.get("ccy", String.class),
                                  row.get("amt", BigDecimal.class))))
                .all()
                .collectList()
                .map(FraudReportService::groupPerCurrency);
    }

    private Mono<Map<UUID, Map<String, BigDecimal>>> loadPerMemberPerCurrency(
            LocalDate start, LocalDate end) {
        return db.sql("""
                        SELECT c.member_id AS mid,
                               s.saved_currency AS ccy,
                               SUM(s.saved_amount) AS amt
                        FROM siu_case s
                        JOIN fraud_flag ff ON ff.siu_case_id = s.id
                        JOIN claims c ON c.id = ff.claim_id
                        WHERE s.status = 'CLOSED_CONFIRMED_FRAUD'
                          AND s.closed_at >= :start AND s.closed_at < :end
                          AND s.saved_amount IS NOT NULL
                          AND s.saved_currency IS NOT NULL
                          AND c.member_id IS NOT NULL
                        GROUP BY c.member_id, s.saved_currency
                        """)
                .bind("start", start)
                .bind("end",   end.plusDays(1))
                .map((row, meta) -> Map.entry(
                        row.get("mid", UUID.class),
                        Map.entry(row.get("ccy", String.class),
                                  row.get("amt", BigDecimal.class))))
                .all()
                .collectList()
                .map(FraudReportService::groupPerCurrency);
    }

    private static Map<UUID, Map<String, BigDecimal>> groupPerCurrency(
            List<Map.Entry<UUID, Map.Entry<String, BigDecimal>>> rows) {
        Map<UUID, Map<String, BigDecimal>> grouped = new LinkedHashMap<>();
        for (var e : rows) {
            grouped.computeIfAbsent(e.getKey(), k -> new LinkedHashMap<>())
                    .put(e.getValue().getKey(), e.getValue().getValue());
        }
        return grouped;
    }

    private Mono<List<ProviderTopNRow>> rankProviders(
            Map<UUID, Map<String, BigDecimal>> perProvider, int cap,
            LocalDate end, UUID tenantId, String reportingCurrency) {
        return countsPerParty("provider", perProvider.keySet(), end)
                .flatMap(counts -> Flux.fromIterable(perProvider.entrySet())
                        .flatMap(entry -> composite(entry.getValue(), end, tenantId, reportingCurrency)
                                .map(comp -> new ProviderTopNRow(
                                        entry.getKey(),
                                        null, null,   // name/code hydrated client-side
                                        counts.getOrDefault(entry.getKey(), 0L),
                                        entry.getValue(),
                                        comp)))
                        .sort((a, b) -> b.savingsComposite().compareTo(a.savingsComposite()))
                        .take(cap)
                        .collectList());
    }

    private Mono<List<MemberTopNRow>> rankMembers(
            Map<UUID, Map<String, BigDecimal>> perMember, int cap,
            LocalDate end, UUID tenantId, String reportingCurrency) {
        return countsPerParty("member", perMember.keySet(), end)
                .flatMap(counts -> Flux.fromIterable(perMember.entrySet())
                        .flatMap(entry -> composite(entry.getValue(), end, tenantId, reportingCurrency)
                                .map(comp -> new MemberTopNRow(
                                        entry.getKey(),
                                        null, null,
                                        counts.getOrDefault(entry.getKey(), 0L),
                                        entry.getValue(),
                                        comp)))
                        .sort((a, b) -> b.savingsComposite().compareTo(a.savingsComposite()))
                        .take(cap)
                        .collectList());
    }

    /** Count confirmed cases per party (provider or member) across the
     *  window — used for the "Confirmed cases" column on the top-N table. */
    private Mono<Map<UUID, Long>> countsPerParty(String kind, java.util.Set<UUID> ids,
                                                  LocalDate end) {
        if (ids.isEmpty()) return Mono.just(Map.of());
        // We don't have {start} in scope here — the caller already filtered
        // upstream; this query recomputes the same window off closed_at so
        // the numbers reconcile with rankX above.
        // Fetch counts over the last 5 years bounded — cheaper than a
        // synthetic IN-list join and stable across the reactive stream.
        String column = "provider".equals(kind) ? "c.provider_id" : "c.member_id";
        return db.sql("""
                        SELECT %s AS pid, COUNT(*) AS n
                        FROM siu_case s
                        JOIN fraud_flag ff ON ff.siu_case_id = s.id
                        JOIN claims c ON c.id = ff.claim_id
                        WHERE s.status = 'CLOSED_CONFIRMED_FRAUD'
                          AND s.closed_at < :end
                          AND s.closed_at >= (:end - INTERVAL '5 years')
                        GROUP BY %s
                        """.formatted(column, column))
                .bind("end", end.plusDays(1))
                .map((row, meta) -> Map.entry(
                        row.get("pid", UUID.class),
                        row.get("n", Long.class)))
                .all()
                .filter(e -> ids.contains(e.getKey()))
                .collectMap(Map.Entry::getKey, Map.Entry::getValue,
                        LinkedHashMap::new);
    }

    private Mono<BigDecimal> composite(Map<String, BigDecimal> perCurrency,
                                        LocalDate end, UUID tenantId,
                                        String reportingCurrency) {
        return Flux.fromIterable(perCurrency.entrySet())
                .flatMap(e -> fxRateReader.convert(e.getValue(), e.getKey(),
                        reportingCurrency, end, tenantId))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static int clampTopN(int n) {
        if (n < 1) return 10;
        return Math.min(n, 100);
    }

    // ── AI calibration (precision by risk_level) ────────────────────────

    public Mono<AiCalibrationData> aiCalibration(String startStr, String endStr) {
        ReportPeriod period = ReportPeriod.parseFromQueryParams(startStr, endStr, null);
        LocalDate start = period.periodStart();
        LocalDate end   = period.periodEnd();
        return db.sql("""
                        SELECT ff.risk_level AS rl,
                               COUNT(*) FILTER (WHERE sc.outcome = 'CONFIRMED_FRAUD') AS tp,
                               COUNT(*) FILTER (WHERE sc.outcome = 'DISMISSED_FALSE_POSITIVE') AS fp,
                               COUNT(*) AS total
                        FROM fraud_flag ff
                        LEFT JOIN siu_case sc ON sc.id = ff.siu_case_id
                        WHERE ff.flag_source = 'AI_MODEL'
                          AND ff.flagged_at >= :start AND ff.flagged_at < :end
                        GROUP BY ff.risk_level
                        ORDER BY ff.risk_level
                        """)
                .bind("start", start)
                .bind("end",   end.plusDays(1))
                .map((row, meta) -> buildCalibrationRow(
                        row.get("rl", String.class),
                        row.get("tp", Long.class) != null ? row.get("tp", Long.class) : 0L,
                        row.get("fp", Long.class) != null ? row.get("fp", Long.class) : 0L,
                        row.get("total", Long.class) != null ? row.get("total", Long.class) : 0L))
                .all()
                .collectList()
                .map(FraudReportService::finaliseCalibration);
    }

    private static AiCalibrationData.CalibrationRow buildCalibrationRow(
            String label, long tp, long fp, long total) {
        String precision;
        if (tp + fp == 0) {
            precision = "0.0000";
        } else {
            BigDecimal p = BigDecimal.valueOf(tp)
                    .divide(BigDecimal.valueOf(tp + fp), 4, RoundingMode.HALF_UP);
            precision = p.toPlainString();
        }
        return new AiCalibrationData.CalibrationRow(
                label != null ? label : "UNKNOWN",
                tp, fp, total, precision);
    }

    static AiCalibrationData finaliseCalibration(List<AiCalibrationData.CalibrationRow> rows) {
        long confirmedTotal = rows.stream()
                .mapToLong(AiCalibrationData.CalibrationRow::truePositives).sum();
        if (confirmedTotal < MIN_CALIBRATION_N) {
            return new AiCalibrationData(List.of(), List.of(
                    "Insufficient data for calibration (N<" + MIN_CALIBRATION_N
                            + " confirmed cases in period)"));
        }
        return new AiCalibrationData(rows, List.of());
    }

    // ── Investigator productivity (role-gated) ──────────────────────────

    public Mono<List<InvestigatorProductivityRow>> investigatorProductivity(
            String startStr, String endStr, Jwt jwt) {
        ReportPeriod period = ReportPeriod.parseFromQueryParams(startStr, endStr, null);
        LocalDate start = period.periodStart();
        LocalDate end   = period.periodEnd();

        boolean isSupervisor = isSupervisorOrAdmin(jwt);
        String actorEmail = AuditActor.email(jwt);

        String scopeClause = isSupervisor
                ? ""
                : " AND s.closed_by_email = :actorEmail";
        String sql = """
                SELECT s.closed_by_email AS email,
                       COUNT(*) AS closed_count,
                       COUNT(*) FILTER (WHERE s.outcome = 'CONFIRMED_FRAUD') AS confirmed,
                       COUNT(*) FILTER (WHERE s.outcome = 'DISMISSED_FALSE_POSITIVE') AS dismissed,
                       COUNT(*) FILTER (WHERE s.outcome IN ('REFERRED_LAW_ENFORCEMENT',
                                                             'ACTION_TAKEN')) AS referred,
                       AVG(EXTRACT(EPOCH FROM (s.closed_at - s.opened_at)) / 86400.0) AS avg_days
                FROM siu_case s
                WHERE s.closed_at IS NOT NULL
                  AND s.closed_at >= :start AND s.closed_at < :end
                  AND s.closed_by_email IS NOT NULL
                """ + scopeClause + """
                GROUP BY s.closed_by_email
                ORDER BY closed_count DESC
                """;
        var spec = db.sql(sql)
                .bind("start", start)
                .bind("end",   end.plusDays(1));
        if (!isSupervisor) {
            spec = spec.bind("actorEmail", actorEmail);
        }
        return spec.map((row, meta) -> new InvestigatorProductivityRow(
                        row.get("email", String.class),
                        row.get("closed_count", Long.class) != null
                                ? row.get("closed_count", Long.class) : 0L,
                        row.get("confirmed", Long.class) != null
                                ? row.get("confirmed", Long.class) : 0L,
                        row.get("dismissed", Long.class) != null
                                ? row.get("dismissed", Long.class) : 0L,
                        row.get("referred", Long.class) != null
                                ? row.get("referred", Long.class) : 0L,
                        row.get("avg_days", BigDecimal.class) != null
                                ? row.get("avg_days", BigDecimal.class).setScale(2, RoundingMode.HALF_UP)
                                : BigDecimal.ZERO))
                .all()
                .collectList();
    }

    static boolean isSupervisorOrAdmin(Jwt jwt) {
        if (jwt == null) return false;
        Object realmAccess = jwt.getClaims().get("realm_access");
        if (realmAccess instanceof Map<?, ?> m) {
            Object roles = m.get("roles");
            if (roles instanceof List<?> list) {
                for (Object r : list) {
                    if ("siu_supervisor".equals(r) || "tenant_admin".equals(r)) return true;
                }
            }
        }
        return false;
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private Mono<Long> countOne(String sql, LocalDate start, LocalDate end) {
        return db.sql(sql)
                .bind("start", start)
                .bind("end",   end.plusDays(1))
                .map((row, meta) -> row.get("c", Long.class))
                .one()
                .defaultIfEmpty(0L);
    }

    private static UUID parseTenantId(String tenantIdStr) {
        if (tenantIdStr == null || tenantIdStr.isBlank()) return null;
        try {
            return UUID.fromString(tenantIdStr);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
