package com.medfund.finance.repository;

import com.medfund.finance.entity.MemberPayable;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface MemberPayableRepository extends R2dbcRepository<MemberPayable, UUID> {

    @Query("SELECT * FROM member_payables WHERE member_id = :memberId ORDER BY recorded_at DESC")
    Flux<MemberPayable> findByMemberId(UUID memberId);

    @Query("""
        SELECT * FROM member_payables
         WHERE member_id = :memberId
           AND status = :status
         ORDER BY recorded_at ASC
        """)
    Flux<MemberPayable> findByMemberIdAndStatus(UUID memberId, String status);

    @Query("SELECT * FROM member_payables WHERE claim_id = :claimId LIMIT 1")
    Mono<MemberPayable> findByClaimId(UUID claimId);

    /**
     * FIFO iteration order used by
     * {@code PaymentService.allocateFifoToPayables} — the oldest open (or
     * partially-applied) payable is consumed first. Status filter is a
     * list so a still-partially-open payable in {@code applied} state
     * (which happens if it was fully applied then reversed and re-opened
     * by a CTC reversal) can also receive slice.
     */
    @Query("""
        SELECT * FROM member_payables
         WHERE member_id = :memberId
           AND status IN (:openStatus, :appliedStatus)
         ORDER BY recorded_at ASC
        """)
    Flux<MemberPayable> findByMemberIdAndStatusInOrderByRecordedAtAsc(
            UUID memberId, String openStatus, String appliedStatus);
}
