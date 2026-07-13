package com.medfund.claims.repository;

import com.medfund.claims.dto.DrugFilterParams;
import com.medfund.claims.entity.Drug;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Dynamic-SQL search powering the drugs catalogue list (server-side
 * sort + pagination). Mirrors the other Query repositories in this
 * service. No joins — drugs are self-contained catalogue rows.
 */
@Repository
public class DrugQueryRepository {

    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "drugName",           "drug_name",
            "drugType",           "drug_type",
            "unitOfMeasurement",  "unit_of_measurement",
            "tariffCode",         "tariff_code",
            "wholesaleCostUsd",   "wholesale_cost_usd",
            "paymentPercentage",  "payment_percentage",
            "doNotPay",           "do_not_pay",
            "isActive",           "is_active",
            "createdAt",          "created_at"
    );

    private final DatabaseClient db;

    public DrugQueryRepository(DatabaseClient db) {
        this.db = db;
    }

    public Flux<Drug> search(DrugFilterParams f, int limit, int offset) {
        boolean hasQ = f.q() != null && !f.q().isBlank();
        String search = hasQ ? "%" + f.q().toLowerCase() + "%" : null;

        String sql = selectClause() + " FROM drugs " + whereClause(f, hasQ)
                + " ORDER BY " + sortClause(f.sortKey(), f.sortDirection())
                + " LIMIT :limit OFFSET :offset";
        var spec = bindFilters(db.sql(sql), f, hasQ, search)
                .bind("limit", limit)
                .bind("offset", offset);
        return spec.map(this::toEntity).all();
    }

    public Mono<Long> count(DrugFilterParams f) {
        boolean hasQ = f.q() != null && !f.q().isBlank();
        String search = hasQ ? "%" + f.q().toLowerCase() + "%" : null;

        String sql = "SELECT COUNT(*) AS total FROM drugs " + whereClause(f, hasQ);
        var spec = bindFilters(db.sql(sql), f, hasQ, search);
        return spec.map(row -> ((Number) row.get("total")).longValue()).one();
    }

    private String selectClause() {
        return """
                SELECT id, drug_name, drug_type, unit_of_measurement, tariff_code,
                       wholesale_cost_zwl, wholesale_cost_usd, payment_percentage,
                       do_not_pay, is_active, created_at, updated_at,
                       created_by, updated_by
                """;
    }

    private String whereClause(DrugFilterParams f, boolean hasQ) {
        StringBuilder sb = new StringBuilder(" WHERE 1 = 1 ");
        if (Boolean.TRUE.equals(f.activeOnly())) {
            sb.append(" AND is_active = TRUE ");
        }
        if (f.drugType() != null && !f.drugType().isBlank()) {
            sb.append(" AND UPPER(drug_type) = UPPER(:drugType) ");
        }
        if (hasQ) {
            sb.append(" AND (LOWER(drug_name) LIKE :search "
                   + "     OR LOWER(COALESCE(tariff_code, '')) LIKE :search "
                   + "     OR LOWER(COALESCE(drug_type, '')) LIKE :search) ");
        }
        return sb.toString();
    }

    private DatabaseClient.GenericExecuteSpec bindFilters(DatabaseClient.GenericExecuteSpec spec,
                                                          DrugFilterParams f,
                                                          boolean hasQ,
                                                          String search) {
        if (f.drugType() != null && !f.drugType().isBlank()) spec = spec.bind("drugType", f.drugType());
        if (hasQ)                                            spec = spec.bind("search", search);
        return spec;
    }

    private String sortClause(String sortKey, String sortDirection) {
        String col = SORT_COLUMNS.getOrDefault(sortKey, "drug_name");
        String dir = "desc".equalsIgnoreCase(sortDirection) ? "DESC" : "ASC";
        return col + " " + dir + " NULLS LAST, id ASC";
    }

    private Drug toEntity(io.r2dbc.spi.Readable row) {
        Drug d = new Drug();
        d.setId(row.get("id", UUID.class));
        d.setDrugName(row.get("drug_name", String.class));
        d.setDrugType(row.get("drug_type", String.class));
        d.setUnitOfMeasurement(row.get("unit_of_measurement", String.class));
        d.setTariffCode(row.get("tariff_code", String.class));
        d.setWholesaleCostZwl(row.get("wholesale_cost_zwl", BigDecimal.class));
        d.setWholesaleCostUsd(row.get("wholesale_cost_usd", BigDecimal.class));
        d.setPaymentPercentage(row.get("payment_percentage", BigDecimal.class));
        d.setDoNotPay(row.get("do_not_pay", Boolean.class));
        d.setIsActive(row.get("is_active", Boolean.class));
        d.setCreatedAt(row.get("created_at", Instant.class));
        d.setUpdatedAt(row.get("updated_at", Instant.class));
        d.setCreatedBy(row.get("created_by", UUID.class));
        d.setUpdatedBy(row.get("updated_by", UUID.class));
        return d;
    }
}
