package com.medfund.finance.repository;

import com.medfund.finance.entity.MemberCostShareLiability;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface MemberCostShareLiabilityRepository extends R2dbcRepository<MemberCostShareLiability, UUID> {

    @Query("SELECT * FROM member_cost_share_liability WHERE claim_id = :claimId LIMIT 1")
    Mono<MemberCostShareLiability> findByClaimId(UUID claimId);

    @Query("""
        SELECT * FROM member_cost_share_liability
         WHERE member_id = :memberId
         ORDER BY created_at DESC
        """)
    Flux<MemberCostShareLiability> findByMemberId(UUID memberId);
}
