package com.medfund.user.repository;

import com.medfund.user.entity.LifePolicy;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface LifePolicyRepository extends R2dbcRepository<LifePolicy, UUID> {

    @Query("SELECT * FROM life_policies WHERE policy_number = :policyNumber")
    Mono<LifePolicy> findByPolicyNumber(String policyNumber);

    @Query("SELECT EXISTS(SELECT 1 FROM life_policies WHERE policy_number = :policyNumber)")
    Mono<Boolean> existsByPolicyNumber(String policyNumber);

    @Query("SELECT * FROM life_policies WHERE scheme_id = :schemeId ORDER BY policy_number")
    Flux<LifePolicy> findBySchemeId(UUID schemeId);

    @Query("SELECT * FROM life_policies WHERE group_id = :groupId ORDER BY policy_number")
    Flux<LifePolicy> findByGroupId(UUID groupId);

    @Query("SELECT * FROM life_policies WHERE insured_member_id = :insuredMemberId ORDER BY policy_number")
    Flux<LifePolicy> findByInsuredMemberId(UUID insuredMemberId);

    @Query("SELECT * FROM life_policies WHERE status = :status ORDER BY created_at DESC")
    Flux<LifePolicy> findByStatus(String status);

    @Query("SELECT * FROM life_policies ORDER BY created_at DESC")
    Flux<LifePolicy> findAllOrderByCreatedAtDesc();

    @Query("""
        SELECT * FROM life_policies
        WHERE LOWER(policy_number) LIKE LOWER(CONCAT('%', :q, '%'))
        ORDER BY policy_number
        """)
    Flux<LifePolicy> search(String q);
}
