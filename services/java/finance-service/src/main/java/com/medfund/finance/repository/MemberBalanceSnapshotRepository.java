package com.medfund.finance.repository;

import com.medfund.finance.entity.MemberBalanceSnapshot;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface MemberBalanceSnapshotRepository extends R2dbcRepository<MemberBalanceSnapshot, UUID> {

    @Query("SELECT * FROM member_balance_snapshot WHERE member_id = :memberId ORDER BY taken_at DESC, payment_run_id")
    Flux<MemberBalanceSnapshot> findByMemberId(UUID memberId);

    @Query("SELECT * FROM member_balance_snapshot WHERE member_id = :memberId AND payment_run_id = :runId")
    Mono<MemberBalanceSnapshot> findByMemberIdAndPaymentRunId(UUID memberId, UUID runId);
}
