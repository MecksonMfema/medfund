package com.medfund.finance.repository;

import com.medfund.finance.entity.MemberCostShareSettlement;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface MemberCostShareSettlementRepository extends R2dbcRepository<MemberCostShareSettlement, UUID> {

    @Query("""
        SELECT * FROM member_cost_share_settlement
         WHERE liability_id = :liabilityId
         ORDER BY settled_at ASC
        """)
    Flux<MemberCostShareSettlement> findByLiabilityId(UUID liabilityId);
}
