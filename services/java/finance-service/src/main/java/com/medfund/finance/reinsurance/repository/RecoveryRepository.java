package com.medfund.finance.reinsurance.repository;

import com.medfund.finance.reinsurance.entity.Recovery;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface RecoveryRepository extends R2dbcRepository<Recovery, UUID> {

    @Query("SELECT * FROM recovery WHERE cession_id = :cessionId")
    Mono<Recovery> findByCessionId(UUID cessionId);

    @Query("SELECT * FROM recovery WHERE status IN (:statuses) ORDER BY created_at DESC")
    Flux<Recovery> findByStatusIn(List<String> statuses);

    /**
     * Bulk EXPECTED → INVOICED transition. Called post-response by the
     * recoveries bordereau export (R8) — the transition establishes the
     * invariant "an invoice has been sent" only once the client has taken
     * delivery of the workbook bytes. Idempotent by the WHERE guard.
     */
    @Modifying
    @Query("UPDATE recovery SET status = 'INVOICED', invoiced_at = :invoicedAt, updated_at = :invoicedAt "
            + "WHERE cession_id IN (:cessionIds) AND status = 'EXPECTED'")
    Mono<Long> markInvoicedByCessionIds(List<UUID> cessionIds, OffsetDateTime invoicedAt);
}
