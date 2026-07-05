package com.medfund.contributions.repository;

import com.medfund.contributions.dto.BalanceRow;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Read-side queries that join running-balance rows to their owning entity
 * (member or group) so the operational pages can render a name + email
 * alongside the outstanding amount and aging.
 *
 * <p>Members and groups live in the tenant schema; running balances live
 * there too. The tenant search-path is set by {@code TenantAwareConnectionFactory}
 * so unqualified table names resolve correctly.
 */
@Repository
@RequiredArgsConstructor
public class BalanceQueryRepository {

    private final DatabaseClient db;

    /**
     * Outstanding balances (positive only). Filter by subject side:
     * {@code "MEMBER"} → ungrouped members only, {@code "GROUP"} →
     * groups only, null → both. Only rows in {@code status IN
     * ('active','suspended')} appear; terminated / deactivated
     * subjects are excluded so the creditor list reflects
     * currently-billable payers, not historical loss records.
     */
    public Flux<BalanceRow> findCreditors(String currency, String subjectType,
                                            String q, int limit, int offset) {
        boolean hasSearch = q != null && !q.isBlank();
        String search = hasSearch ? "%" + q.toLowerCase() + "%" : null;
        String sql = creditorBaseQuery(hasSearch, subjectType)
                + " ORDER BY balance DESC LIMIT :limit OFFSET :offset";
        var spec = db.sql(sql)
                .bind("currency", currency)
                .bind("limit", limit)
                .bind("offset", offset);
        if (hasSearch) spec = spec.bind("search", search);
        return spec.map(this::toRow).all();
    }

    public Mono<Long> countCreditors(String currency, String subjectType, String q) {
        boolean hasSearch = q != null && !q.isBlank();
        String search = hasSearch ? "%" + q.toLowerCase() + "%" : null;
        String sql = "SELECT COUNT(*) AS total FROM (" + creditorBaseQuery(hasSearch, subjectType) + ") sub";
        var spec = db.sql(sql).bind("currency", currency);
        if (hasSearch) spec = spec.bind("search", search);
        return spec.map(row -> ((Number) row.get("total")).longValue()).one();
    }

    /** Aged balances — last activity older than {@code minAgeDays}. */
    public Flux<BalanceRow> findAged(String currency, int minAgeDays, String q, int limit, int offset) {
        boolean hasSearch = q != null && !q.isBlank();
        String search = hasSearch ? "%" + q.toLowerCase() + "%" : null;
        String sql = agedBaseQuery(hasSearch)
                + " ORDER BY last_payment_at NULLS FIRST, balance DESC LIMIT :limit OFFSET :offset";
        var spec = db.sql(sql)
                .bind("currency", currency)
                .bind("minAge", String.valueOf(Math.max(minAgeDays, 0)))
                .bind("limit", limit)
                .bind("offset", offset);
        if (hasSearch) spec = spec.bind("search", search);
        return spec.map(this::toRow).all();
    }

    public Mono<Long> countAged(String currency, int minAgeDays, String q) {
        boolean hasSearch = q != null && !q.isBlank();
        String search = hasSearch ? "%" + q.toLowerCase() + "%" : null;
        String sql = "SELECT COUNT(*) AS total FROM (" + agedBaseQuery(hasSearch) + ") sub";
        var spec = db.sql(sql)
                .bind("currency", currency)
                .bind("minAge", String.valueOf(Math.max(minAgeDays, 0)));
        if (hasSearch) spec = spec.bind("search", search);
        return spec.map(row -> ((Number) row.get("total")).longValue()).one();
    }

    // ── SQL builders ──────────────────────────────────────────────────────

    private String creditorBaseQuery(boolean hasSearch, String subjectType) {
        String memberSearch = hasSearch
                ? " AND (LOWER(m.first_name || ' ' || m.last_name) LIKE :search OR LOWER(COALESCE(m.email, '')) LIKE :search OR LOWER(COALESCE(m.member_number, '')) LIKE :search) "
                : "";
        String groupSearch = hasSearch
                ? " AND (LOWER(g.name) LIKE :search OR LOWER(COALESCE(g.registration_number, '')) LIKE :search) "
                : "";

        // MEMBER half — ungrouped members only (group_id IS NULL); a
        // grouped member is billed through their group, so surfacing
        // them here would double-count with the GROUP row for that
        // group. Status filter: only currently-billable subjects
        // (active / suspended) appear on the operational creditors
        // list — terminated / deactivated rows belong on the bad-debts
        // page, not here.
        String memberBlock = """
                SELECT 'MEMBER' AS subject_type, m.id AS subject_id,
                       m.member_number AS subject_code,
                       (m.first_name || ' ' || m.last_name) AS subject_name,
                       m.email AS subject_email,
                       mrb.currency_code, mrb.balance,
                       mrb.last_charge_at, mrb.last_payment_at
                  FROM member_running_balance mrb JOIN members m ON m.id = mrb.member_id
                 WHERE mrb.currency_code = :currency AND mrb.balance > 0
                   AND m.group_id IS NULL
                   AND m.status IN ('active','suspended')
                """ + memberSearch;

        String groupBlockSql = groupBlock(
                "AND grb.balance > 0 AND g.status IN ('active','suspended')")
                + groupSearch;

        // Route by subjectType. Null → both halves union'd (default).
        boolean memberOnly = "MEMBER".equalsIgnoreCase(subjectType);
        boolean groupOnly  = "GROUP".equalsIgnoreCase(subjectType);
        if (memberOnly) return memberBlock;
        if (groupOnly)  return groupBlockSql;
        return memberBlock + " UNION ALL " + groupBlockSql;
    }

    private String agedBaseQuery(boolean hasSearch) {
        String memberSearch = hasSearch
                ? " AND (LOWER(m.first_name || ' ' || m.last_name) LIKE :search OR LOWER(COALESCE(m.email, '')) LIKE :search OR LOWER(COALESCE(m.member_number, '')) LIKE :search) "
                : "";
        String groupSearch = hasSearch
                ? " AND (LOWER(g.name) LIKE :search OR LOWER(COALESCE(g.registration_number, '')) LIKE :search) "
                : "";
        return """
                SELECT 'MEMBER' AS subject_type, m.id AS subject_id,
                       m.member_number AS subject_code,
                       (m.first_name || ' ' || m.last_name) AS subject_name,
                       m.email AS subject_email,
                       mrb.currency_code, mrb.balance,
                       mrb.last_charge_at, mrb.last_payment_at
                  FROM member_running_balance mrb JOIN members m ON m.id = mrb.member_id
                 WHERE mrb.currency_code = :currency AND mrb.balance > 0
                   AND COALESCE(mrb.last_payment_at, mrb.last_charge_at, NOW()) < NOW() - (:minAge || ' days')::interval
                """ + memberSearch + """
                UNION ALL
                """ + groupBlock(
                "AND grb.balance > 0 "
                + "AND COALESCE(grb.last_payment_at, grb.last_charge_at, NOW()) < NOW() - (:minAge || ' days')::interval"
              ) + groupSearch;
    }

    /**
     * Common GROUP-half of the union. The subject_email is the liaison's
     * email, COALESCEd across the three possible liaison sources (member,
     * staff user, pure liaison) with a final fallback to {@code g.email}
     * (V038 — the group-level contact address that receives statements
     * when no liaison is assigned). Staff users live in the platform-
     * wide {@code public.staff_users} table; the other three sources
     * live in the tenant schema. The {@code extraWhere} expression is
     * appended into the group block's WHERE clause so callers can layer
     * balance/aging filters.
     */
    private String groupBlock(String extraWhere) {
        return """
                SELECT 'GROUP' AS subject_type, g.id AS subject_id,
                       g.registration_number AS subject_code,
                       g.name AS subject_name,
                       COALESCE(m_lia.email, su_lia.email, gl_lia.email, g.email) AS subject_email,
                       grb.currency_code, grb.balance,
                       grb.last_charge_at, grb.last_payment_at
                  FROM group_running_balance grb
                  JOIN groups g                   ON g.id = grb.group_id
                  LEFT JOIN members m_lia         ON g.liaison_kind = 'MEMBER'  AND m_lia.id = g.liaison_user_id
                  LEFT JOIN public.staff_users su_lia ON g.liaison_kind = 'STAFF' AND su_lia.id = g.liaison_user_id
                  LEFT JOIN group_liaisons gl_lia ON g.liaison_kind = 'LIAISON' AND gl_lia.id = g.liaison_user_id
                 WHERE grb.currency_code = :currency
                """ + " " + extraWhere + " ";
    }

    private BalanceRow toRow(io.r2dbc.spi.Readable row) {
        return new BalanceRow(
                row.get("subject_type",   String.class),
                row.get("subject_id",     UUID.class),
                row.get("subject_code",   String.class),
                row.get("subject_name",   String.class),
                row.get("subject_email",  String.class),
                row.get("currency_code",  String.class),
                row.get("balance",        BigDecimal.class),
                // R2DBC-Postgres returns TIMESTAMPTZ as OffsetDateTime;
                // ask for Instant explicitly so the driver converts.
                // Raw casts here threw ClassCastException at runtime —
                // same pattern as the StatementService fix.
                row.get("last_charge_at",  Instant.class),
                row.get("last_payment_at", Instant.class));
    }
}
