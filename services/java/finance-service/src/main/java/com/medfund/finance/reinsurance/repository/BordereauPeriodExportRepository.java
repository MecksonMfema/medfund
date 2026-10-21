package com.medfund.finance.reinsurance.repository;

import com.medfund.finance.reinsurance.entity.BordereauPeriodExport;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * The composite business key {@code (reinsurer_id, treaty_id, report_key,
 * year, quarter)} allows a NULL {@code treaty_id} (aggregate-over-all-treaties
 * exports), so lookup queries are split to handle that branch explicitly —
 * {@code = NULL} never matches in SQL.
 */
public interface BordereauPeriodExportRepository extends R2dbcRepository<BordereauPeriodExport, UUID> {

    @Query("SELECT * FROM bordereau_period_export "
            + "WHERE reinsurer_id = :reinsurerId "
            + "  AND treaty_id = :treatyId "
            + "  AND report_key = :reportKey "
            + "  AND year = :year "
            + "  AND quarter = :quarter")
    Mono<BordereauPeriodExport> findByCompositeKey(UUID reinsurerId, UUID treatyId,
                                                   String reportKey, int year, int quarter);

    @Query("SELECT * FROM bordereau_period_export "
            + "WHERE reinsurer_id = :reinsurerId "
            + "  AND treaty_id IS NULL "
            + "  AND report_key = :reportKey "
            + "  AND year = :year "
            + "  AND quarter = :quarter")
    Mono<BordereauPeriodExport> findByCompositeKeyNoTreaty(UUID reinsurerId,
                                                           String reportKey, int year, int quarter);
}
