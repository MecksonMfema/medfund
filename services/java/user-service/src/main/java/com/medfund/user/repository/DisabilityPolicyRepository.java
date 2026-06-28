package com.medfund.user.repository;

import com.medfund.user.entity.DisabilityPolicy;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface DisabilityPolicyRepository extends R2dbcRepository<DisabilityPolicy, UUID> {

    @Query("SELECT * FROM disability_policies WHERE policy_number = :policyNumber")
    Mono<DisabilityPolicy> findByPolicyNumber(String policyNumber);

    @Query("SELECT EXISTS(SELECT 1 FROM disability_policies WHERE policy_number = :policyNumber)")
    Mono<Boolean> existsByPolicyNumber(String policyNumber);

    @Query("SELECT * FROM disability_policies WHERE scheme_id = :schemeId ORDER BY policy_number")
    Flux<DisabilityPolicy> findBySchemeId(UUID schemeId);

    @Query("SELECT * FROM disability_policies WHERE group_id = :groupId ORDER BY policy_number")
    Flux<DisabilityPolicy> findByGroupId(UUID groupId);

    @Query("SELECT * FROM disability_policies WHERE insured_member_id = :insuredMemberId ORDER BY policy_number")
    Flux<DisabilityPolicy> findByInsuredMemberId(UUID insuredMemberId);

    @Query("SELECT * FROM disability_policies WHERE status = :status ORDER BY created_at DESC")
    Flux<DisabilityPolicy> findByStatus(String status);

    @Query("SELECT * FROM disability_policies ORDER BY created_at DESC")
    Flux<DisabilityPolicy> findAllOrderByCreatedAtDesc();

    @Query("""
        SELECT * FROM disability_policies
        WHERE LOWER(policy_number) LIKE LOWER(CONCAT('%', :q, '%'))
        ORDER BY policy_number
        """)
    Flux<DisabilityPolicy> search(String q);
}
