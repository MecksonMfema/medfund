package com.medfund.contributions.premium.repository;

import com.medfund.contributions.premium.dto.NewBusinessRegisterRow;
import com.medfund.contributions.premium.dto.PremiumRegisterRow;
import com.medfund.contributions.premium.dto.UprMovementRow;
import com.medfund.shared.report.PerCurrencyTotal;
import io.r2dbc.spi.Parameters;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Raw-SQL projections for the three Phase 12 §B underwriting reports:
 * UPR Movement, Premium Register, New Business Register. Rows are
 * native-currency (parent-plan invariant #1); each report additionally
 * exposes a {@code perCurrency} aggregate feeding the envelope's
 * per-currency ledger totals.
 *
 * <p>The Register + New-Business queries UNION-ALL across the six annual
 * policy tables (life / funeral / disability / travel / vehicle /
 * property) plus HEALTH-branch {@code contributions} so a single query
 * covers every insurance line without per-source dispatch in the
 * service layer. Optional {@code insurance_line} + {@code policy_source}
 * predicates trim the branches at plan time.
 */
@Repository
@RequiredArgsConstructor
public class PremiumReportQueryRepository {

    private final DatabaseClient db;

    // ── UPR Movement ────────────────────────────────────────────────────────

    /**
     * UPR movement for the window — four-way FULL OUTER JOIN of the
     * opening / written / earned / endorsement-delta CTEs. The identity
     * {@code opening + written − earned + delta = closing} is reconciled
     * by the SQL so the client renders the movement table verbatim.
     *
     * <p>Optional {@code insuranceLine} filter narrows the report to a
     * single line — matches the Angular filter row.
     */
    public Flux<UprMovementRow> uprMovementRows(LocalDate periodStart, LocalDate periodEnd,
                                                String insuranceLine) {
        String sql = """
                WITH opening AS (
                    SELECT insurance_line, currency_code,
                           SUM(written_amount - COALESCE(earned_at_period_end, 0)) AS opening_upr
                      FROM earning_schedule
                     WHERE period_start < :periodStart
                       AND period_end  >= :periodStart
                       AND (:insuranceLine::varchar IS NULL OR insurance_line = :insuranceLine::varchar)
                     GROUP BY insurance_line, currency_code
                ),
                written_in_period AS (
                    SELECT insurance_line, currency_code,
                           SUM(written_amount) AS written
                      FROM earning_schedule
                     WHERE period_start >= :periodStart
                       AND period_start <= :periodEnd
                       AND (:insuranceLine::varchar IS NULL OR insurance_line = :insuranceLine::varchar)
                     GROUP BY insurance_line, currency_code
                ),
                earned_in_period AS (
                    SELECT insurance_line, currency_code,
                           SUM(earned_at_period_end) AS earned
                      FROM earning_schedule
                     WHERE period_end   >= :periodStart
                       AND period_end   <= :periodEnd
                       AND earned_at_period_end IS NOT NULL
                       AND (:insuranceLine::varchar IS NULL OR insurance_line = :insuranceLine::varchar)
                     GROUP BY insurance_line, currency_code
                ),
                endorsement_delta AS (
                    SELECT insurance_line, currency_code,
                           SUM(written_amount) AS delta
                      FROM earning_schedule
                     WHERE is_endorsement = TRUE
                       AND created_at::date >= :periodStart
                       AND created_at::date <= :periodEnd
                       AND (:insuranceLine::varchar IS NULL OR insurance_line = :insuranceLine::varchar)
                     GROUP BY insurance_line, currency_code
                )
                SELECT COALESCE(o.insurance_line, w.insurance_line, e.insurance_line, d.insurance_line)
                           AS insurance_line,
                       COALESCE(o.currency_code,  w.currency_code,  e.currency_code,  d.currency_code)
                           AS currency_code,
                       COALESCE(o.opening_upr, 0) AS opening_upr,
                       COALESCE(w.written,     0) AS written_premium,
                       COALESCE(e.earned,      0) AS earned_premium,
                       COALESCE(d.delta,       0) AS endorsement_delta,
                       COALESCE(o.opening_upr, 0)
                         + COALESCE(w.written, 0)
                         - COALESCE(e.earned,  0)
                         + COALESCE(d.delta,   0) AS closing_upr
                  FROM opening o
                  FULL OUTER JOIN written_in_period    w USING (insurance_line, currency_code)
                  FULL OUTER JOIN earned_in_period     e USING (insurance_line, currency_code)
                  FULL OUTER JOIN endorsement_delta    d USING (insurance_line, currency_code)
                 ORDER BY insurance_line, currency_code
                """;
        return db.sql(sql)
                .bind("periodStart",  periodStart)
                .bind("periodEnd",    periodEnd)
                .bind("insuranceLine", insuranceLine != null ? insuranceLine : Parameters.in(String.class))
                .map((row, meta) -> new UprMovementRow(
                        row.get("insurance_line", String.class),
                        row.get("currency_code",  String.class),
                        nz(row.get("opening_upr",       BigDecimal.class)),
                        nz(row.get("written_premium",   BigDecimal.class)),
                        nz(row.get("earned_premium",    BigDecimal.class)),
                        nz(row.get("endorsement_delta", BigDecimal.class)),
                        nz(row.get("closing_upr",       BigDecimal.class))))
                .all();
    }

    /**
     * Per-currency native totals for UPR — feeds the envelope's
     * {@code perCurrency} map (parent-plan G18). {@code totalAmount} is
     * the {@code closing_upr} which is the report's headline number; the
     * {@code rowCount} carries the (line × currency) count.
     */
    public Mono<Map<String, PerCurrencyTotal>> uprMovementPerCurrencyTotals(
            LocalDate periodStart, LocalDate periodEnd, String insuranceLine) {
        String sql = """
                WITH opening AS (
                    SELECT currency_code,
                           SUM(written_amount - COALESCE(earned_at_period_end, 0)) AS opening_upr
                      FROM earning_schedule
                     WHERE period_start < :periodStart
                       AND period_end  >= :periodStart
                       AND (:insuranceLine::varchar IS NULL OR insurance_line = :insuranceLine::varchar)
                     GROUP BY currency_code
                ),
                written_in_period AS (
                    SELECT currency_code, SUM(written_amount) AS written
                      FROM earning_schedule
                     WHERE period_start >= :periodStart
                       AND period_start <= :periodEnd
                       AND (:insuranceLine::varchar IS NULL OR insurance_line = :insuranceLine::varchar)
                     GROUP BY currency_code
                ),
                earned_in_period AS (
                    SELECT currency_code, SUM(earned_at_period_end) AS earned
                      FROM earning_schedule
                     WHERE period_end >= :periodStart
                       AND period_end <= :periodEnd
                       AND earned_at_period_end IS NOT NULL
                       AND (:insuranceLine::varchar IS NULL OR insurance_line = :insuranceLine::varchar)
                     GROUP BY currency_code
                ),
                endorsement_delta AS (
                    SELECT currency_code, SUM(written_amount) AS delta
                      FROM earning_schedule
                     WHERE is_endorsement = TRUE
                       AND created_at::date >= :periodStart
                       AND created_at::date <= :periodEnd
                       AND (:insuranceLine::varchar IS NULL OR insurance_line = :insuranceLine::varchar)
                     GROUP BY currency_code
                )
                SELECT COALESCE(o.currency_code, w.currency_code, e.currency_code, d.currency_code)
                           AS currency_code,
                       COALESCE(o.opening_upr, 0)
                         + COALESCE(w.written, 0)
                         - COALESCE(e.earned,  0)
                         + COALESCE(d.delta,   0) AS total_amount,
                       COUNT(*)                    AS row_count
                  FROM opening o
                  FULL OUTER JOIN written_in_period    w USING (currency_code)
                  FULL OUTER JOIN earned_in_period     e USING (currency_code)
                  FULL OUTER JOIN endorsement_delta    d USING (currency_code)
                 GROUP BY currency_code, o.opening_upr, w.written, e.earned, d.delta
                """;
        return db.sql(sql)
                .bind("periodStart",  periodStart)
                .bind("periodEnd",    periodEnd)
                .bind("insuranceLine", insuranceLine != null ? insuranceLine : Parameters.in(String.class))
                .map((row, meta) -> {
                    String cc = row.get("currency_code", String.class);
                    return Map.entry(cc != null ? cc : "",
                            new PerCurrencyTotal(
                                    nz(row.get("total_amount", BigDecimal.class)),
                                    row.get("row_count", Long.class) != null
                                            ? row.get("row_count", Long.class)
                                            : 0L));
                })
                .all()
                .filter(e -> !e.getKey().isBlank())
                .collectMap(Map.Entry::getKey, Map.Entry::getValue);
    }

    // ── Premium Register ────────────────────────────────────────────────────

    /**
     * Per-policy-per-period rows for the Premium Register. Joins
     * {@code earning_schedule} rows overlapping the window with the
     * appropriate policy table via a seven-way UNION-ALL enrichment CTE,
     * plus {@code ifrs17_portfolio} / {@code ifrs17_cohort} + {@code
     * members} / {@code schemes} for the friendly labels.
     */
    public Flux<PremiumRegisterRow> premiumRegisterRows(LocalDate periodStart, LocalDate periodEnd,
                                                        String insuranceLine) {
        String sql = """
                WITH policy_enrichment AS (
                    SELECT id AS policy_id, 'LIFE_POLICY'::text AS policy_source,
                           bound_at, coverage_start, coverage_end,
                           insured_member_id AS member_id, scheme_id,
                           renewed_from_policy_id
                      FROM life_policies
                    UNION ALL
                    SELECT id, 'FUNERAL_POLICY'::text,
                           bound_at, coverage_start, coverage_end,
                           principal_member_id, scheme_id, renewed_from_policy_id
                      FROM funeral_policies
                    UNION ALL
                    SELECT id, 'DISABILITY_POLICY'::text,
                           bound_at, coverage_start, coverage_end,
                           insured_member_id, scheme_id, renewed_from_policy_id
                      FROM disability_policies
                    UNION ALL
                    SELECT id, 'TRAVEL_POLICY'::text,
                           bound_at, trip_start_date AS coverage_start, trip_end_date AS coverage_end,
                           traveler_member_id, scheme_id, renewed_from_policy_id
                      FROM travel_policies
                    UNION ALL
                    SELECT id, 'VEHICLE_POLICY'::text,
                           bound_at, coverage_start, coverage_end,
                           owner_member_id, scheme_id, renewed_from_policy_id
                      FROM vehicles
                    UNION ALL
                    SELECT id, 'PROPERTY_POLICY'::text,
                           bound_at, coverage_start, coverage_end,
                           owner_member_id, scheme_id, renewed_from_policy_id
                      FROM properties
                    UNION ALL
                    SELECT id, 'CONTRIBUTION'::text,
                           created_at AS bound_at,
                           period_start AS coverage_start,
                           period_end   AS coverage_end,
                           member_id, scheme_id, NULL::uuid
                      FROM contributions
                )
                SELECT es.policy_id,
                       es.policy_source,
                       COALESCE(NULLIF(TRIM(COALESCE(m.first_name, '') || ' ' || COALESCE(m.last_name, '')), ''), '')
                           AS member_name,
                       es.insurance_line,
                       COALESCE(s.name, '')                                     AS scheme_name,
                       es.currency_code,
                       es.written_amount                                        AS written_premium,
                       COALESCE(es.earned_at_period_end, 0)                     AS earned_in_period,
                       es.written_amount - COALESCE(es.earned_at_period_end, 0) AS unearned_at_period_end,
                       pe.bound_at,
                       pe.coverage_start,
                       pe.coverage_end,
                       (pe.renewed_from_policy_id IS NULL
                        AND (es.policy_source <> 'CONTRIBUTION'
                             OR mfc.first_at IS NULL
                             OR (mfc.first_at::date >= :periodStart AND mfc.first_at::date <= :periodEnd)))
                           AS is_new_business,
                       COALESCE(ifp.name, '')                                    AS portfolio_name,
                       COALESCE(ifc.name, '')                                    AS cohort_name,
                       es.period_start,
                       es.period_end
                  FROM earning_schedule es
                  LEFT JOIN policy_enrichment pe
                    ON pe.policy_id = es.policy_id AND pe.policy_source = es.policy_source
                  LEFT JOIN members            m   ON m.id  = pe.member_id
                  LEFT JOIN schemes            s   ON s.id  = pe.scheme_id
                  LEFT JOIN ifrs17_portfolio   ifp ON ifp.id = es.portfolio_id
                  LEFT JOIN ifrs17_cohort      ifc ON ifc.id = es.cohort_id
                  LEFT JOIN member_first_contribution mfc ON mfc.member_id = pe.member_id
                 WHERE es.period_start <= :periodEnd
                   AND es.period_end   >= :periodStart
                   AND es.is_endorsement = FALSE
                   AND (:insuranceLine::varchar IS NULL OR es.insurance_line = :insuranceLine::varchar)
                 ORDER BY es.period_start, es.policy_source, es.policy_id
                """;
        return db.sql(sql)
                .bind("periodStart",  periodStart)
                .bind("periodEnd",    periodEnd)
                .bind("insuranceLine", insuranceLine != null ? insuranceLine : Parameters.in(String.class))
                .map((row, meta) -> new PremiumRegisterRow(
                        row.get("policy_id", java.util.UUID.class),
                        row.get("policy_source", String.class),
                        row.get("member_name", String.class),
                        row.get("insurance_line", String.class),
                        row.get("scheme_name", String.class),
                        row.get("currency_code", String.class),
                        nz(row.get("written_premium", BigDecimal.class)),
                        nz(row.get("earned_in_period", BigDecimal.class)),
                        nz(row.get("unearned_at_period_end", BigDecimal.class)),
                        row.get("bound_at", OffsetDateTime.class),
                        row.get("coverage_start", LocalDate.class),
                        row.get("coverage_end",   LocalDate.class),
                        Boolean.TRUE.equals(row.get("is_new_business", Boolean.class)),
                        row.get("portfolio_name", String.class),
                        row.get("cohort_name",    String.class),
                        row.get("period_start",   LocalDate.class),
                        row.get("period_end",     LocalDate.class)))
                .all();
    }

    /** Per-currency native totals for the Premium Register — feeds the envelope. */
    public Mono<Map<String, PerCurrencyTotal>> premiumRegisterPerCurrencyTotals(
            LocalDate periodStart, LocalDate periodEnd, String insuranceLine) {
        String sql = """
                SELECT currency_code                                AS currency_code,
                       SUM(written_amount)                          AS total_amount,
                       COUNT(*)                                     AS row_count
                  FROM earning_schedule
                 WHERE period_start <= :periodEnd
                   AND period_end   >= :periodStart
                   AND is_endorsement = FALSE
                   AND (:insuranceLine::varchar IS NULL OR insurance_line = :insuranceLine::varchar)
                 GROUP BY currency_code
                """;
        return db.sql(sql)
                .bind("periodStart",  periodStart)
                .bind("periodEnd",    periodEnd)
                .bind("insuranceLine", insuranceLine != null ? insuranceLine : Parameters.in(String.class))
                .map((row, meta) -> Map.entry(
                        row.get("currency_code", String.class),
                        new PerCurrencyTotal(
                                nz(row.get("total_amount", BigDecimal.class)),
                                row.get("row_count", Long.class) != null
                                        ? row.get("row_count", Long.class)
                                        : 0L)))
                .all()
                .collectMap(Map.Entry::getKey, Map.Entry::getValue);
    }

    // ── New Business Register ───────────────────────────────────────────────

    /**
     * New-business rows for the window per U6. Annual-bind lines: filter
     * by {@code bound_at BETWEEN periodStart AND periodEnd AND
     * renewed_from_policy_id IS NULL}. HEALTH: filter by the member's
     * first-ever Contribution instant via the {@code
     * member_first_contribution} view. {@code writtenPremium} for HEALTH
     * new-business rows is the sum of Contributions in the window for
     * that member (since the "policy" is nominal for HEALTH).
     */
    public Flux<NewBusinessRegisterRow> newBusinessRows(LocalDate periodStart, LocalDate periodEnd,
                                                        String insuranceLine) {
        String sql = """
                WITH annual AS (
                    SELECT lp.id AS policy_id, 'LIFE_POLICY'::text AS policy_source,
                           lp.insured_member_id AS member_id, lp.scheme_id,
                           lp.bound_at, lp.coverage_start, lp.coverage_end,
                           lp.written_premium, lp.written_premium_currency AS currency_code,
                           lp.portfolio_id, lp.cohort_id, 'LIFE' AS insurance_line
                      FROM life_policies lp
                     WHERE lp.renewed_from_policy_id IS NULL
                       AND lp.bound_at::date >= :periodStart
                       AND lp.bound_at::date <= :periodEnd
                       AND lp.written_premium IS NOT NULL
                    UNION ALL
                    SELECT fp.id, 'FUNERAL_POLICY',
                           fp.principal_member_id, fp.scheme_id,
                           fp.bound_at, fp.coverage_start, fp.coverage_end,
                           fp.written_premium, fp.written_premium_currency,
                           fp.portfolio_id, fp.cohort_id, 'FUNERAL'
                      FROM funeral_policies fp
                     WHERE fp.renewed_from_policy_id IS NULL
                       AND fp.bound_at::date >= :periodStart
                       AND fp.bound_at::date <= :periodEnd
                       AND fp.written_premium IS NOT NULL
                    UNION ALL
                    SELECT dp.id, 'DISABILITY_POLICY',
                           dp.insured_member_id, dp.scheme_id,
                           dp.bound_at, dp.coverage_start, dp.coverage_end,
                           dp.written_premium, dp.written_premium_currency,
                           dp.portfolio_id, dp.cohort_id, 'DISABILITY'
                      FROM disability_policies dp
                     WHERE dp.renewed_from_policy_id IS NULL
                       AND dp.bound_at::date >= :periodStart
                       AND dp.bound_at::date <= :periodEnd
                       AND dp.written_premium IS NOT NULL
                    UNION ALL
                    SELECT tp.id, 'TRAVEL_POLICY',
                           tp.traveler_member_id, tp.scheme_id,
                           tp.bound_at, tp.trip_start_date, tp.trip_end_date,
                           tp.written_premium, tp.written_premium_currency,
                           tp.portfolio_id, tp.cohort_id, 'TRAVEL'
                      FROM travel_policies tp
                     WHERE tp.renewed_from_policy_id IS NULL
                       AND tp.bound_at::date >= :periodStart
                       AND tp.bound_at::date <= :periodEnd
                       AND tp.written_premium IS NOT NULL
                    UNION ALL
                    SELECT v.id, 'VEHICLE_POLICY',
                           v.owner_member_id, v.scheme_id,
                           v.bound_at, v.coverage_start, v.coverage_end,
                           v.written_premium, v.written_premium_currency,
                           v.portfolio_id, v.cohort_id, 'VEHICLE'
                      FROM vehicles v
                     WHERE v.renewed_from_policy_id IS NULL
                       AND v.bound_at::date >= :periodStart
                       AND v.bound_at::date <= :periodEnd
                       AND v.written_premium IS NOT NULL
                    UNION ALL
                    SELECT pr.id, 'PROPERTY_POLICY',
                           pr.owner_member_id, pr.scheme_id,
                           pr.bound_at, pr.coverage_start, pr.coverage_end,
                           pr.written_premium, pr.written_premium_currency,
                           pr.portfolio_id, pr.cohort_id, 'PROPERTY'
                      FROM properties pr
                     WHERE pr.renewed_from_policy_id IS NULL
                       AND pr.bound_at::date >= :periodStart
                       AND pr.bound_at::date <= :periodEnd
                       AND pr.written_premium IS NOT NULL
                ),
                health_new AS (
                    SELECT c.id AS policy_id, 'CONTRIBUTION'::text AS policy_source,
                           c.member_id, c.scheme_id,
                           c.created_at AS bound_at,
                           c.period_start AS coverage_start,
                           c.period_end   AS coverage_end,
                           c.amount       AS written_premium,
                           c.currency_code,
                           c.portfolio_id, c.cohort_id, 'HEALTH' AS insurance_line
                      FROM contributions c
                      JOIN member_first_contribution mfc ON mfc.member_id = c.member_id
                     WHERE mfc.first_at::date >= :periodStart
                       AND mfc.first_at::date <= :periodEnd
                       AND c.id = (
                           SELECT c2.id FROM contributions c2
                            WHERE c2.member_id = c.member_id
                            ORDER BY c2.created_at ASC
                            LIMIT 1
                       )
                ),
                combined AS (
                    SELECT * FROM annual
                    UNION ALL
                    SELECT * FROM health_new
                )
                SELECT c.policy_id,
                       c.policy_source,
                       COALESCE(m.member_number, '')                          AS member_number,
                       COALESCE(NULLIF(TRIM(COALESCE(m.first_name, '') || ' ' || COALESCE(m.last_name, '')), ''), '')
                           AS member_name,
                       c.insurance_line,
                       COALESCE(s.name, '')                                    AS scheme_name,
                       c.bound_at,
                       c.written_premium,
                       c.currency_code,
                       COALESCE(ifp.name, '')                                  AS portfolio_name,
                       COALESCE(ifc.name, '')                                  AS cohort_name,
                       c.coverage_start,
                       c.coverage_end
                  FROM combined c
                  LEFT JOIN members            m   ON m.id  = c.member_id
                  LEFT JOIN schemes            s   ON s.id  = c.scheme_id
                  LEFT JOIN ifrs17_portfolio   ifp ON ifp.id = c.portfolio_id
                  LEFT JOIN ifrs17_cohort      ifc ON ifc.id = c.cohort_id
                 WHERE (:insuranceLine::varchar IS NULL OR c.insurance_line = :insuranceLine::varchar)
                 ORDER BY c.bound_at, c.insurance_line, c.policy_id
                """;
        return db.sql(sql)
                .bind("periodStart",  periodStart)
                .bind("periodEnd",    periodEnd)
                .bind("insuranceLine", insuranceLine != null ? insuranceLine : Parameters.in(String.class))
                .map((row, meta) -> new NewBusinessRegisterRow(
                        row.get("policy_id", java.util.UUID.class),
                        row.get("policy_source", String.class),
                        row.get("member_number", String.class),
                        row.get("member_name",   String.class),
                        row.get("insurance_line", String.class),
                        row.get("scheme_name",    String.class),
                        row.get("bound_at", OffsetDateTime.class),
                        nz(row.get("written_premium", BigDecimal.class)),
                        row.get("currency_code", String.class),
                        row.get("portfolio_name", String.class),
                        row.get("cohort_name",    String.class),
                        row.get("coverage_start", LocalDate.class),
                        row.get("coverage_end",   LocalDate.class)))
                .all();
    }

    /** Per-currency native totals for New Business — feeds the envelope. */
    public Mono<Map<String, PerCurrencyTotal>> newBusinessPerCurrencyTotals(
            LocalDate periodStart, LocalDate periodEnd, String insuranceLine) {
        String sql = """
                WITH annual AS (
                    SELECT lp.written_premium_currency AS currency_code, lp.written_premium AS written
                      FROM life_policies lp
                     WHERE lp.renewed_from_policy_id IS NULL
                       AND lp.bound_at::date >= :periodStart
                       AND lp.bound_at::date <= :periodEnd
                       AND lp.written_premium IS NOT NULL
                       AND (:insuranceLine::varchar IS NULL OR :insuranceLine::varchar = 'LIFE')
                    UNION ALL
                    SELECT fp.written_premium_currency, fp.written_premium
                      FROM funeral_policies fp
                     WHERE fp.renewed_from_policy_id IS NULL
                       AND fp.bound_at::date >= :periodStart
                       AND fp.bound_at::date <= :periodEnd
                       AND fp.written_premium IS NOT NULL
                       AND (:insuranceLine::varchar IS NULL OR :insuranceLine::varchar = 'FUNERAL')
                    UNION ALL
                    SELECT dp.written_premium_currency, dp.written_premium
                      FROM disability_policies dp
                     WHERE dp.renewed_from_policy_id IS NULL
                       AND dp.bound_at::date >= :periodStart
                       AND dp.bound_at::date <= :periodEnd
                       AND dp.written_premium IS NOT NULL
                       AND (:insuranceLine::varchar IS NULL OR :insuranceLine::varchar = 'DISABILITY')
                    UNION ALL
                    SELECT tp.written_premium_currency, tp.written_premium
                      FROM travel_policies tp
                     WHERE tp.renewed_from_policy_id IS NULL
                       AND tp.bound_at::date >= :periodStart
                       AND tp.bound_at::date <= :periodEnd
                       AND tp.written_premium IS NOT NULL
                       AND (:insuranceLine::varchar IS NULL OR :insuranceLine::varchar = 'TRAVEL')
                    UNION ALL
                    SELECT v.written_premium_currency, v.written_premium
                      FROM vehicles v
                     WHERE v.renewed_from_policy_id IS NULL
                       AND v.bound_at::date >= :periodStart
                       AND v.bound_at::date <= :periodEnd
                       AND v.written_premium IS NOT NULL
                       AND (:insuranceLine::varchar IS NULL OR :insuranceLine::varchar = 'VEHICLE')
                    UNION ALL
                    SELECT pr.written_premium_currency, pr.written_premium
                      FROM properties pr
                     WHERE pr.renewed_from_policy_id IS NULL
                       AND pr.bound_at::date >= :periodStart
                       AND pr.bound_at::date <= :periodEnd
                       AND pr.written_premium IS NOT NULL
                       AND (:insuranceLine::varchar IS NULL OR :insuranceLine::varchar = 'PROPERTY')
                    UNION ALL
                    SELECT c.currency_code, c.amount
                      FROM contributions c
                      JOIN member_first_contribution mfc ON mfc.member_id = c.member_id
                     WHERE mfc.first_at::date >= :periodStart
                       AND mfc.first_at::date <= :periodEnd
                       AND (:insuranceLine::varchar IS NULL OR :insuranceLine::varchar = 'HEALTH')
                       AND c.id = (
                           SELECT c2.id FROM contributions c2
                            WHERE c2.member_id = c.member_id
                            ORDER BY c2.created_at ASC
                            LIMIT 1
                       )
                )
                SELECT currency_code, SUM(written) AS total_amount, COUNT(*) AS row_count
                  FROM annual
                 GROUP BY currency_code
                """;
        return db.sql(sql)
                .bind("periodStart",  periodStart)
                .bind("periodEnd",    periodEnd)
                .bind("insuranceLine", insuranceLine != null ? insuranceLine : Parameters.in(String.class))
                .map((row, meta) -> Map.entry(
                        row.get("currency_code", String.class),
                        new PerCurrencyTotal(
                                nz(row.get("total_amount", BigDecimal.class)),
                                row.get("row_count", Long.class) != null
                                        ? row.get("row_count", Long.class)
                                        : 0L)))
                .all()
                .filter(e -> e.getKey() != null && !e.getKey().isBlank())
                .collectMap(Map.Entry::getKey, Map.Entry::getValue);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static BigDecimal nz(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }
}
