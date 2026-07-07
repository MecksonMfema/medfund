package com.medfund.user.repository;

import com.medfund.user.entity.PendingGroupChange;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.UUID;

public interface PendingGroupChangeRepository extends ReactiveCrudRepository<PendingGroupChange, UUID> {

    Flux<PendingGroupChange> findByMemberId(UUID memberId);

    Flux<PendingGroupChange> findByStatus(String status);

    /**
     * Sweep target for ScheduledChangesExecutor — every APPROVED row
     * whose effective_date has arrived. The composite index
     * {@code idx_pending_group_changes_ready} covers this query.
     */
    @Query("SELECT * FROM pending_group_changes WHERE status = 'APPROVED' AND effective_date <= :today")
    Flux<PendingGroupChange> findReadyToApply(LocalDate today);

    /**
     * Live-request guard used at request time to reject a second
     * pending group change for the same member. Only PENDING or
     * APPROVED rows count; REJECTED / CANCELLED / APPLIED can pile
     * up as history without blocking new requests.
     */
    @Query("""
            SELECT COUNT(*) FROM pending_group_changes
             WHERE member_id = :memberId AND status IN ('PENDING','APPROVED')
            """)
    Mono<Long> countLiveByMemberId(UUID memberId);
}
