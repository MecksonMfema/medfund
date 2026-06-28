package com.medfund.user.repository;

import com.medfund.user.entity.TravelPolicy;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface TravelPolicyRepository extends R2dbcRepository<TravelPolicy, UUID> {

    @Query("SELECT * FROM travel_policies WHERE policy_number = :policyNumber")
    Mono<TravelPolicy> findByPolicyNumber(String policyNumber);

    @Query("SELECT EXISTS(SELECT 1 FROM travel_policies WHERE policy_number = :policyNumber)")
    Mono<Boolean> existsByPolicyNumber(String policyNumber);

    @Query("SELECT * FROM travel_policies WHERE scheme_id = :schemeId ORDER BY policy_number")
    Flux<TravelPolicy> findBySchemeId(UUID schemeId);

    @Query("SELECT * FROM travel_policies WHERE group_id = :groupId ORDER BY policy_number")
    Flux<TravelPolicy> findByGroupId(UUID groupId);

    @Query("SELECT * FROM travel_policies WHERE traveler_member_id = :travelerMemberId ORDER BY policy_number")
    Flux<TravelPolicy> findByTravelerMemberId(UUID travelerMemberId);

    @Query("SELECT * FROM travel_policies WHERE status = :status ORDER BY created_at DESC")
    Flux<TravelPolicy> findByStatus(String status);

    @Query("SELECT * FROM travel_policies ORDER BY created_at DESC")
    Flux<TravelPolicy> findAllOrderByCreatedAtDesc();

    @Query("""
        SELECT * FROM travel_policies
        WHERE LOWER(policy_number) LIKE LOWER(CONCAT('%', :q, '%'))
        ORDER BY policy_number
        """)
    Flux<TravelPolicy> search(String q);
}
