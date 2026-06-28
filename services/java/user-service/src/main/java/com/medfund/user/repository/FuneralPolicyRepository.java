package com.medfund.user.repository;

import com.medfund.user.entity.FuneralPolicy;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface FuneralPolicyRepository extends R2dbcRepository<FuneralPolicy, UUID> {

    @Query("SELECT * FROM funeral_policies WHERE policy_number = :policyNumber")
    Mono<FuneralPolicy> findByPolicyNumber(String policyNumber);

    @Query("SELECT EXISTS(SELECT 1 FROM funeral_policies WHERE policy_number = :policyNumber)")
    Mono<Boolean> existsByPolicyNumber(String policyNumber);

    @Query("SELECT * FROM funeral_policies WHERE scheme_id = :schemeId ORDER BY policy_number")
    Flux<FuneralPolicy> findBySchemeId(UUID schemeId);

    @Query("SELECT * FROM funeral_policies WHERE group_id = :groupId ORDER BY policy_number")
    Flux<FuneralPolicy> findByGroupId(UUID groupId);

    @Query("SELECT * FROM funeral_policies WHERE principal_member_id = :principalMemberId ORDER BY policy_number")
    Flux<FuneralPolicy> findByPrincipalMemberId(UUID principalMemberId);

    @Query("SELECT * FROM funeral_policies WHERE status = :status ORDER BY created_at DESC")
    Flux<FuneralPolicy> findByStatus(String status);

    @Query("SELECT * FROM funeral_policies ORDER BY created_at DESC")
    Flux<FuneralPolicy> findAllOrderByCreatedAtDesc();

    @Query("""
        SELECT * FROM funeral_policies
        WHERE LOWER(policy_number) LIKE LOWER(CONCAT('%', :q, '%'))
        ORDER BY policy_number
        """)
    Flux<FuneralPolicy> search(String q);
}
