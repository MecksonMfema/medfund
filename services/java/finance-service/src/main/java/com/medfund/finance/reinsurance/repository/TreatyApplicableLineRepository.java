package com.medfund.finance.reinsurance.repository;

import com.medfund.finance.reinsurance.entity.TreatyApplicableLine;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Composite-key repository for {@code treaty_applicable_line}. Values are
 * short and immutable — the only operations are "add", "remove", and
 * "list". No update surface.
 */
@Repository
@RequiredArgsConstructor
public class TreatyApplicableLineRepository {

    private final DatabaseClient db;

    public Flux<TreatyApplicableLine> findByTreatyId(UUID treatyId) {
        return db.sql("SELECT treaty_id, insurance_line FROM treaty_applicable_line WHERE treaty_id = :treatyId ORDER BY insurance_line")
                .bind("treatyId", treatyId)
                .map(TreatyApplicableLineRepository::mapRow)
                .all();
    }

    public Mono<Long> countByTreatyId(UUID treatyId) {
        return db.sql("SELECT COUNT(*) AS c FROM treaty_applicable_line WHERE treaty_id = :treatyId")
                .bind("treatyId", treatyId)
                .map((row, meta) -> row.get("c", Long.class))
                .one()
                .defaultIfEmpty(0L);
    }

    public Mono<TreatyApplicableLine> insert(UUID treatyId, String insuranceLine) {
        return db.sql("""
                INSERT INTO treaty_applicable_line (treaty_id, insurance_line)
                     VALUES (:treatyId, :insuranceLine)
                  RETURNING treaty_id, insurance_line
                """)
                .bind("treatyId", treatyId)
                .bind("insuranceLine", insuranceLine)
                .map(TreatyApplicableLineRepository::mapRow)
                .one();
    }

    public Mono<Long> delete(UUID treatyId, String insuranceLine) {
        return db.sql("DELETE FROM treaty_applicable_line WHERE treaty_id = :treatyId AND insurance_line = :insuranceLine")
                .bind("treatyId", treatyId)
                .bind("insuranceLine", insuranceLine)
                .fetch()
                .rowsUpdated();
    }

    private static TreatyApplicableLine mapRow(Row row, RowMetadata meta) {
        TreatyApplicableLine l = new TreatyApplicableLine();
        l.setTreatyId(row.get("treaty_id", UUID.class));
        l.setInsuranceLine(row.get("insurance_line", String.class));
        return l;
    }
}
