package com.medfund.contributions.service;

import com.medfund.contributions.dto.InvoiceContributionRow;
import com.medfund.contributions.dto.InvoiceListRow;
import com.medfund.contributions.dto.InvoicesPage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Read-projection for {@code GET /api/v1/invoices} (plan §1) and
 * {@code GET /api/v1/invoices/{id}/contributions} (plan §2).
 *
 * <p>All queries unqualified — {@code TenantSchemaInterceptor} sets
 * {@code search_path} on the connection, per
 * {@code bug_public_prefix_silent_rollback}.
 *
 * <p>All aggregation server-side per {@code feedback_stats_serverside}.
 * Names always projected per {@code feedback_no_raw_id_inputs}.
 */
@Service
public class InvoiceListService {

    private static final Logger log = LoggerFactory.getLogger(InvoiceListService.class);

    /**
     * Whitelisted sort keys for the listing — any value outside this set
     * collapses to the default {@code issuedAt}. Prevents SQL-injection
     * via the {@code sortKey} query param.
     */
    private static final java.util.Map<String, String> SORTABLE = java.util.Map.of(
            "issuedAt",     "i.issued_at",
            "committedAt",  "i.committed_at",
            "periodStart",  "i.period_start",
            "dueDate",      "i.due_date",
            "totalAmount",  "i.total_amount",
            "invoiceNumber","i.invoice_number",
            "holderName",   "holder_name",
            "status",       "i.status");

    private final DatabaseClient db;

    public InvoiceListService(DatabaseClient db) {
        this.db = db;
    }

    public Mono<InvoicesPage> list(Filter f) {
        int page = Math.max(0, f.page());
        int size = Math.max(1, Math.min(200, f.size()));
        String sortColumn = SORTABLE.getOrDefault(f.sortKey(), "i.issued_at");
        String direction = "asc".equalsIgnoreCase(f.sortDirection()) ? "ASC" : "DESC";

        String selectSql = """
                SELECT i.id, i.invoice_number, i.status, i.currency_code, i.total_amount,
                       i.period_start, i.period_end, i.due_date, i.issued_at, i.paid_at,
                       i.committed_at, i.opening_balance, i.closing_balance,
                       i.group_id, i.member_id,
                       COALESCE(g.name, (m.first_name || ' ' || m.last_name)) AS holder_name,
                       COALESCE(g.registration_number, m.member_number)        AS holder_number,
                       CASE WHEN i.group_id IS NOT NULL THEN 'GROUP' ELSE 'INDIVIDUAL' END AS holder_type,
                       COALESCE((SELECT string_agg(DISTINCT s2.name, ', ' ORDER BY s2.name)
                                   FROM contributions c2
                                   JOIN schemes s2 ON s2.id = c2.scheme_id
                                  WHERE c2.invoice_id = i.id), '') AS scheme_names,
                       COALESCE((SELECT string_agg(DISTINCT s3.insurance_line, ',' ORDER BY s3.insurance_line)
                                   FROM contributions c3
                                   JOIN schemes s3 ON s3.id = c3.scheme_id
                                  WHERE c3.invoice_id = i.id), '') AS insurance_lines,
                       (SELECT COUNT(*) FROM contributions c4 WHERE c4.invoice_id = i.id) AS contribution_count,
                       EXISTS (SELECT 1 FROM invoice_pdfs p WHERE p.invoice_id = i.id) AS pdf_ready
                  FROM invoices i
                  LEFT JOIN groups  g ON g.id = i.group_id
                  LEFT JOIN members m ON m.id = i.member_id
                """;

        String whereSql = """
                 WHERE (:year   IS NULL OR EXTRACT(YEAR  FROM i.period_start) = :year)
                   AND (:month  IS NULL OR EXTRACT(MONTH FROM i.period_start) = :month)
                   AND (:status IS NULL OR i.status = :status)
                   AND (:currency IS NULL OR i.currency_code = :currency)
                   AND (:holderType IS NULL
                        OR (:holderType = 'GROUP'      AND i.group_id  IS NOT NULL)
                        OR (:holderType = 'INDIVIDUAL' AND i.member_id IS NOT NULL))
                   AND (:line IS NULL OR EXISTS (
                        SELECT 1 FROM contributions c5
                        JOIN schemes s5 ON s5.id = c5.scheme_id
                        WHERE c5.invoice_id = i.id AND s5.insurance_line = :line))
                   AND (:q IS NULL OR (
                        g.name                ILIKE '%' || :q || '%' OR
                        g.registration_number ILIKE '%' || :q || '%' OR
                        m.first_name          ILIKE '%' || :q || '%' OR
                        m.last_name           ILIKE '%' || :q || '%' OR
                        m.member_number       ILIKE '%' || :q || '%' OR
                        i.invoice_number      ILIKE '%' || :q || '%'))
                """;

        String orderSql = " ORDER BY " + sortColumn + " " + direction + ", i.id " + direction;
        String pageSql  = " LIMIT " + size + " OFFSET " + (page * size);

        var pageQuery = db.sql(selectSql + whereSql + orderSql + pageSql);
        var countQuery = db.sql("SELECT COUNT(*) AS n FROM invoices i " +
                "LEFT JOIN groups g ON g.id = i.group_id " +
                "LEFT JOIN members m ON m.id = i.member_id " + whereSql);

        pageQuery  = bindFilters(pageQuery, f);
        countQuery = bindFilters(countQuery, f);

        Mono<Long> total = countQuery
                .map(row -> row.get("n", Long.class))
                .one()
                .defaultIfEmpty(0L);

        Mono<List<InvoiceListRow>> rows = pageQuery
                .map(row -> mapRow(row))
                .all().collectList();

        return Mono.zip(rows, total).map(t -> {
            List<InvoiceListRow> content = t.getT1();
            long count = t.getT2();
            int totalPages = (int) Math.max(1, (count + size - 1) / size);
            return new InvoicesPage(content, count, page, size, totalPages);
        });
    }

    private static org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec bindFilters(
            org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec spec, Filter f) {
        // R2DBC rejects .bind("x", null) — must use bindNull(name, type)
        // so the driver knows the SQL parameter's column type. The query's
        // `:year IS NULL OR ...` predicate then matches the null binding
        // and skips the filter.
        spec = (f.year()       != null) ? spec.bind("year",       f.year())       : spec.bindNull("year",       Integer.class);
        spec = (f.month()      != null) ? spec.bind("month",      f.month())      : spec.bindNull("month",      Integer.class);
        spec = (f.status()     != null) ? spec.bind("status",     f.status())     : spec.bindNull("status",     String.class);
        spec = (f.currency()   != null) ? spec.bind("currency",   f.currency())   : spec.bindNull("currency",   String.class);
        spec = (f.holderType() != null) ? spec.bind("holderType", f.holderType()) : spec.bindNull("holderType", String.class);
        spec = (f.insuranceLine() != null) ? spec.bind("line", f.insuranceLine()) : spec.bindNull("line",       String.class);
        spec = (f.q() != null && !f.q().isBlank()) ? spec.bind("q", f.q())        : spec.bindNull("q",          String.class);
        return spec;
    }

    private static InvoiceListRow mapRow(io.r2dbc.spi.Readable row) {
        String linesRaw = row.get("insurance_lines", String.class);
        List<String> lines = (linesRaw == null || linesRaw.isBlank())
                ? List.of()
                : Arrays.stream(linesRaw.split(",")).map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();

        Number contribCount = row.get("contribution_count", Number.class);
        Boolean pdfReady = row.get("pdf_ready", Boolean.class);

        return new InvoiceListRow(
                row.get("id", UUID.class),
                row.get("invoice_number", String.class),
                row.get("holder_type", String.class),
                row.get("holder_name", String.class),
                row.get("holder_number", String.class),
                row.get("scheme_names", String.class),
                lines,
                row.get("period_start", LocalDate.class),
                row.get("period_end", LocalDate.class),
                row.get("total_amount", BigDecimal.class),
                row.get("currency_code", String.class),
                contribCount != null ? contribCount.intValue() : 0,
                row.get("status", String.class),
                row.get("due_date", LocalDate.class),
                row.get("issued_at", Instant.class),
                row.get("paid_at", Instant.class),
                row.get("committed_at", Instant.class),
                row.get("opening_balance", BigDecimal.class),
                row.get("closing_balance", BigDecimal.class),
                pdfReady != null ? pdfReady : false);
    }

    /**
     * Per-invoice contributions, joined with member/dependant/scheme
     * names + insurance_line. Powers the per-scheme breakdown sub-table
     * on the statement detail page.
     */
    public reactor.core.publisher.Flux<InvoiceContributionRow> contributionsFor(UUID invoiceId) {
        return db.sql("""
                SELECT c.id AS contribution_id, c.amount, c.currency_code,
                       m.member_number, (m.first_name || ' ' || m.last_name) AS member_name,
                       d.id AS dependant_id,
                       CASE WHEN d.id IS NULL THEN 'MEMBER' ELSE 'DEPENDANT' END AS person_type,
                       (COALESCE(d.first_name, '') || ' ' || COALESCE(d.last_name, '')) AS dependant_name,
                       s.name AS scheme_name, s.insurance_line,
                       ag.name AS age_band
                  FROM contributions c
                  LEFT JOIN members    m  ON m.id = c.member_id
                  LEFT JOIN dependants d  ON d.id = c.dependant_id
                  LEFT JOIN schemes    s  ON s.id = c.scheme_id
                  LEFT JOIN age_groups ag ON ag.id = c.age_group_id
                 WHERE c.invoice_id = :iid
                 ORDER BY s.name, m.member_number, person_type DESC
                """)
                .bind("iid", invoiceId)
                .map(row -> new InvoiceContributionRow(
                        row.get("contribution_id", UUID.class),
                        row.get("member_number", String.class),
                        row.get("member_name", String.class),
                        row.get("person_type", String.class),
                        nullIfBlank(row.get("dependant_name", String.class)),
                        row.get("scheme_name", String.class),
                        row.get("insurance_line", String.class),
                        row.get("age_band", String.class),
                        row.get("amount", BigDecimal.class),
                        row.get("currency_code", String.class)))
                .all();
    }

    private static String nullIfBlank(String s) {
        return (s == null || s.trim().isEmpty()) ? null : s.trim();
    }

    public record Filter(
            Integer year, Integer month,
            String status, String currency, String holderType, String insuranceLine,
            String q,
            int page, int size,
            String sortKey, String sortDirection
    ) {}
}
