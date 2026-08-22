package com.medfund.finance.reinsurance.repository;

import com.medfund.finance.reinsurance.entity.TreatyParticipant;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Composite-key repository for {@code treaty_participant}. R2DBC's
 * {@code ReactiveCrudRepository} doesn't support composite keys, so
 * every operation is hand-rolled via {@link DatabaseClient}.
 */
@Repository
@RequiredArgsConstructor
public class TreatyParticipantRepository {

    private final DatabaseClient db;

    public Flux<TreatyParticipant> findByTreatyId(UUID treatyId) {
        return db.sql("""
                SELECT treaty_id, reinsurer_id, share_pct, share_role, created_at
                  FROM treaty_participant
                 WHERE treaty_id = :treatyId
                 ORDER BY share_role, share_pct DESC
                """)
                .bind("treatyId", treatyId)
                .map(TreatyParticipantRepository::mapRow)
                .all();
    }

    public Mono<TreatyParticipant> findByTreatyIdAndReinsurerId(UUID treatyId, UUID reinsurerId) {
        return db.sql("""
                SELECT treaty_id, reinsurer_id, share_pct, share_role, created_at
                  FROM treaty_participant
                 WHERE treaty_id = :treatyId AND reinsurer_id = :reinsurerId
                """)
                .bind("treatyId", treatyId)
                .bind("reinsurerId", reinsurerId)
                .map(TreatyParticipantRepository::mapRow)
                .one();
    }

    public Mono<TreatyParticipant> insert(TreatyParticipant p) {
        return db.sql("""
                INSERT INTO treaty_participant (treaty_id, reinsurer_id, share_pct, share_role)
                     VALUES (:treatyId, :reinsurerId, :sharePct, :shareRole)
                  RETURNING treaty_id, reinsurer_id, share_pct, share_role, created_at
                """)
                .bind("treatyId", p.getTreatyId())
                .bind("reinsurerId", p.getReinsurerId())
                .bind("sharePct", p.getSharePct())
                .bind("shareRole", p.getShareRole())
                .map(TreatyParticipantRepository::mapRow)
                .one();
    }

    public Mono<TreatyParticipant> update(TreatyParticipant p) {
        return db.sql("""
                UPDATE treaty_participant
                   SET share_pct = :sharePct,
                       share_role = :shareRole
                 WHERE treaty_id = :treatyId AND reinsurer_id = :reinsurerId
                RETURNING treaty_id, reinsurer_id, share_pct, share_role, created_at
                """)
                .bind("treatyId", p.getTreatyId())
                .bind("reinsurerId", p.getReinsurerId())
                .bind("sharePct", p.getSharePct())
                .bind("shareRole", p.getShareRole())
                .map(TreatyParticipantRepository::mapRow)
                .one();
    }

    public Mono<Long> delete(UUID treatyId, UUID reinsurerId) {
        return db.sql("DELETE FROM treaty_participant WHERE treaty_id = :treatyId AND reinsurer_id = :reinsurerId")
                .bind("treatyId", treatyId)
                .bind("reinsurerId", reinsurerId)
                .fetch()
                .rowsUpdated();
    }

    public Mono<BigDecimal> sumShareByTreatyId(UUID treatyId) {
        return db.sql("SELECT COALESCE(SUM(share_pct), 0)::numeric AS total FROM treaty_participant WHERE treaty_id = :treatyId")
                .bind("treatyId", treatyId)
                .map((row, meta) -> row.get("total", BigDecimal.class))
                .one()
                .defaultIfEmpty(BigDecimal.ZERO);
    }

    private static TreatyParticipant mapRow(Row row, RowMetadata meta) {
        TreatyParticipant p = new TreatyParticipant();
        p.setTreatyId(row.get("treaty_id", UUID.class));
        p.setReinsurerId(row.get("reinsurer_id", UUID.class));
        p.setSharePct(row.get("share_pct", BigDecimal.class));
        p.setShareRole(row.get("share_role", String.class));
        p.setCreatedAt(row.get("created_at", OffsetDateTime.class));
        return p;
    }
}
