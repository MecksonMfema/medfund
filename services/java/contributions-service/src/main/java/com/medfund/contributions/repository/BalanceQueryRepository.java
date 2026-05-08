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

    /** Outstanding balances (positive only), unioned across members and groups. */
    public Flux<BalanceRow> findCreditors(String currency, String q, int limit, int offset) {
        boolean hasSearch = q != null && !q.isBlank();
        String search = hasSearch ? "%" + q.toLowerCase() + "%" : null;
        String sql = creditorBaseQuery(hasSearch) + " ORDER BY balance DESC LIMIT :limit OFFSET :offset";
        var spec = db.sql(sql)
                .bind("currency", currency)
                .bind("limit", limit)
                .bind("offset", offset);
        if (hasSearch) spec = spec.bind("search", search);
        return spec.map(this::toRow).all();
    }

    public Mono<Long> countCreditors(String currency, String q) {
        boolean hasSearch = q != null && !q.isBlank();
        String search = hasSearch ? "%" + q.toLowerCase() + "%" : null;
        String sql = "SELECT COUNT(*) AS total FROM (" + creditorBaseQuery(hasSearch) + ") sub";
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

    private String creditorBaseQuery(boolean hasSearch) {
        String memberSearch = hasSearch
                ? " AND (LOWER(m.first_name || ' ' || m.last_name) LIKE :search OR LOWER(COALESCE(m.email, '')) LIKE :search OR LOWER(COALESCE(m.member_number, '')) LIKE :search) "
                : "";
        String groupSearch = hasSearch
                ? " AND (LOWER(g.name) LIKE :search OR LOWER(COALESCE(g.contact_email, '')) LIKE :search OR LOWER(COALESCE(g.registration_number, '')) LIKE :search) "
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
                """ + memberSearch + """
                UNION ALL
                SELECT 'GROUP' AS subject_type, g.id AS subject_id,
                       g.registration_number AS subject_code,
                       g.name AS subject_name,
                       g.contact_email AS subject_email,
                       grb.currency_code, grb.balance,
                       grb.last_charge_at, grb.last_payment_at
                  FROM group_running_balance grb JOIN groups g ON g.id = grb.group_id
                 WHERE grb.currency_code = :currency AND grb.balance > 0
                """ + groupSearch;
    }

    private String agedBaseQuery(boolean hasSearch) {
        String memberSearch = hasSearch
                ? " AND (LOWER(m.first_name || ' ' || m.last_name) LIKE :search OR LOWER(COALESCE(m.email, '')) LIKE :search OR LOWER(COALESCE(m.member_number, '')) LIKE :search) "
                : "";
        String groupSearch = hasSearch
                ? " AND (LOWER(g.name) LIKE :search OR LOWER(COALESCE(g.contact_email, '')) LIKE :search OR LOWER(COALESCE(g.registration_number, '')) LIKE :search) "
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
                SELECT 'GROUP' AS subject_type, g.id AS subject_id,
                       g.registration_number AS subject_code,
                       g.name AS subject_name,
                       g.contact_email AS subject_email,
                       grb.currency_code, grb.balance,
                       grb.last_charge_at, grb.last_payment_at
                  FROM group_running_balance grb JOIN groups g ON g.id = grb.group_id
                 WHERE grb.currency_code = :currency AND grb.balance > 0
                   AND COALESCE(grb.last_payment_at, grb.last_charge_at, NOW()) < NOW() - (:minAge || ' days')::interval
                """ + groupSearch;
    }

    private BalanceRow toRow(io.r2dbc.spi.Readable row) {
        return new BalanceRow(
                (String) row.get("subject_type"),
                (UUID) row.get("subject_id"),
                (String) row.get("subject_code"),
                (String) row.get("subject_name"),
                (String) row.get("subject_email"),
                (String) row.get("currency_code"),
                (BigDecimal) row.get("balance"),
                (Instant) row.get("last_charge_at"),
                (Instant) row.get("last_payment_at"));
    }
}
