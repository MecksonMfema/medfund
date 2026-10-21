package com.medfund.user.repository;

import com.medfund.user.entity.PolicyUnitLedger;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface PolicyUnitLedgerRepository extends R2dbcRepository<PolicyUnitLedger, UUID> {

    @Query("""
            SELECT * FROM policy_unit_ledger
            WHERE policy_id = :policyId
            ORDER BY transaction_date DESC, created_at DESC
            """)
    Flux<PolicyUnitLedger> findByPolicyId(UUID policyId);

    @Query("""
            SELECT * FROM policy_unit_ledger
            WHERE fund_id = :fundId
            ORDER BY transaction_date DESC, created_at DESC
            """)
    Flux<PolicyUnitLedger> findByFundId(UUID fundId);
}
