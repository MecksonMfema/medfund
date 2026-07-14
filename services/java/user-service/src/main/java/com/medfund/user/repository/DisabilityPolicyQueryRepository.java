package com.medfund.user.repository;

import com.medfund.user.dto.DisabilityPolicyFilterParams;
import com.medfund.user.dto.DisabilityPolicyRow;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Repository
public class DisabilityPolicyQueryRepository {

    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "policyNumber",       "p.policy_number",
            "monthlyBenefit",     "p.monthly_benefit",
            "waitingPeriodDays",  "p.waiting_period_days",
            "benefitPeriod",      "p.benefit_period",
            "status",             "p.status",
            "schemeName",         "COALESCE(s.name, '')",
            "insuredMemberName",  "COALESCE(m.last_name || ' ' || m.first_name, '')",
            "createdAt",          "p.created_at"
    );

    private final DatabaseClient db;

    public DisabilityPolicyQueryRepository(DatabaseClient db) {
        this.db = db;
    }

    public Flux<DisabilityPolicyRow> search(DisabilityPolicyFilterParams f, int limit, int offset) {
        boolean hasQ = f.q() != null && !f.q().isBlank();
        String search = hasQ ? "%" + f.q().toLowerCase() + "%" : null;

        String sql = """
                SELECT p.id, p.scheme_id, p.insured_member_id,
                       p.policy_number, p.occupation_hazard_class,
                       p.waiting_period_days, p.benefit_period, p.monthly_benefit,
                       p.status,
                       p.billing_override_amount, p.created_at, p.updated_at,
                       s.name AS scheme_name,
                       m.first_name AS insured_first_name,
                       m.last_name  AS insured_last_name,
                       m.member_number AS insured_member_number
                  FROM disability_policies p
                  LEFT JOIN schemes s ON s.id = p.scheme_id
                  LEFT JOIN members m ON m.id = p.insured_member_id
                """
                + whereClause(f, hasQ)
                + " ORDER BY " + sortClause(f.sortKey(), f.sortDirection())
                + " LIMIT :limit OFFSET :offset";

        var spec = bindFilters(db.sql(sql), f, hasQ, search)
                .bind("limit", limit)
                .bind("offset", offset);
        return spec.map(this::toRow).all();
    }

    public Mono<Long> count(DisabilityPolicyFilterParams f) {
        boolean hasQ = f.q() != null && !f.q().isBlank();
        String search = hasQ ? "%" + f.q().toLowerCase() + "%" : null;

        String sql = """
                SELECT COUNT(*) AS total FROM disability_policies p
                  LEFT JOIN schemes s ON s.id = p.scheme_id
                  LEFT JOIN members m ON m.id = p.insured_member_id
                """
                + whereClause(f, hasQ);
        var spec = bindFilters(db.sql(sql), f, hasQ, search);
        return spec.map(row -> ((Number) row.get("total")).longValue()).one();
    }

    private String whereClause(DisabilityPolicyFilterParams f, boolean hasQ) {
        StringBuilder sb = new StringBuilder(" WHERE 1 = 1 ");
        if (f.status() != null && !f.status().isBlank()) {
            sb.append(" AND p.status = :status ");
        }
        if (f.schemeId() != null) {
            sb.append(" AND p.scheme_id = :schemeId ");
        }
        if (hasQ) {
            sb.append(" AND (LOWER(p.policy_number) LIKE :search "
                    + "     OR LOWER(COALESCE(s.name, '')) LIKE :search "
                    + "     OR LOWER(COALESCE(m.first_name || ' ' || m.last_name, '')) LIKE :search "
                    + "     OR LOWER(COALESCE(m.last_name  || ' ' || m.first_name, '')) LIKE :search "
                    + "     OR LOWER(COALESCE(m.member_number, '')) LIKE :search) ");
        }
        return sb.toString();
    }

    private DatabaseClient.GenericExecuteSpec bindFilters(DatabaseClient.GenericExecuteSpec spec,
                                                          DisabilityPolicyFilterParams f,
                                                          boolean hasQ,
                                                          String search) {
        if (f.status() != null && !f.status().isBlank()) {
            spec = spec.bind("status", f.status());
        }
        if (f.schemeId() != null) {
            spec = spec.bind("schemeId", f.schemeId());
        }
        if (hasQ) spec = spec.bind("search", search);
        return spec;
    }

    private String sortClause(String sortKey, String sortDirection) {
        String col = SORT_COLUMNS.getOrDefault(sortKey, "p.created_at");
        String dir = "asc".equalsIgnoreCase(sortDirection) ? "ASC" : "DESC";
        return col + " " + dir + " NULLS LAST, p.id ASC";
    }

    private DisabilityPolicyRow toRow(io.r2dbc.spi.Readable row) {
        String insuredName = VehicleQueryRepository.fullName(
                row.get("insured_first_name", String.class),
                row.get("insured_last_name", String.class),
                row.get("insured_member_number", String.class));
        return new DisabilityPolicyRow(
                row.get("id", UUID.class),
                row.get("scheme_id", UUID.class),
                row.get("scheme_name", String.class),
                row.get("insured_member_id", UUID.class),
                insuredName,
                row.get("policy_number", String.class),
                row.get("occupation_hazard_class", String.class),
                row.get("waiting_period_days", Integer.class),
                row.get("benefit_period", String.class),
                row.get("monthly_benefit", BigDecimal.class),
                row.get("status", String.class),
                row.get("billing_override_amount", BigDecimal.class),
                row.get("created_at", Instant.class),
                row.get("updated_at", Instant.class)
        );
    }
}
