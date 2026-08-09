package com.medfund.finance.repository;

import com.medfund.finance.entity.MemberPayableApplication;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface MemberPayableApplicationRepository
        extends R2dbcRepository<MemberPayableApplication, UUID> {

    @Query("""
        SELECT * FROM member_payable_applications
         WHERE member_payable_id = :payableId
         ORDER BY applied_at DESC
        """)
    Flux<MemberPayableApplication> findByMemberPayableId(UUID payableId);

    @Query("""
        SELECT * FROM member_payable_applications
         WHERE source_type = :sourceType
           AND source_id   = :sourceId
         ORDER BY applied_at DESC
        """)
    Flux<MemberPayableApplication> findBySource(String sourceType, UUID sourceId);
}
