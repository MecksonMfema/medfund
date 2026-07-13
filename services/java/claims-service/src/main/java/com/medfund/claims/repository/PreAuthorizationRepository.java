package com.medfund.claims.repository;

import com.medfund.claims.entity.PreAuthorization;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface PreAuthorizationRepository extends R2dbcRepository<PreAuthorization, UUID> {

    @Query("SELECT * FROM pre_authorizations WHERE member_id = :memberId ORDER BY created_at DESC")
    Flux<PreAuthorization> findByMemberId(UUID memberId);

    @Query("SELECT * FROM pre_authorizations WHERE dependant_id = :dependantId ORDER BY created_at DESC")
    Flux<PreAuthorization> findByDependantId(UUID dependantId);

    @Query("SELECT * FROM pre_authorizations WHERE auth_number = :authNumber")
    Mono<PreAuthorization> findByAuthNumber(String authNumber);

    @Query("SELECT * FROM pre_authorizations WHERE status = :status ORDER BY created_at DESC")
    Flux<PreAuthorization> findByStatus(String status);

    /**
     * Look up a member-level pre-auth (dependant_id IS NULL) so a member and
     * one of their dependants can hold their own pre-auth for the same tariff
     * code without one shadowing the other.
     */
    @Query("SELECT * FROM pre_authorizations WHERE member_id = :memberId AND dependant_id IS NULL AND tariff_code = :tariffCode AND status = :status")
    Mono<PreAuthorization> findByMemberIdAndTariffCodeAndStatus(UUID memberId, String tariffCode, String status);

    @Query("SELECT * FROM pre_authorizations WHERE dependant_id = :dependantId AND tariff_code = :tariffCode AND status = :status")
    Mono<PreAuthorization> findByDependantIdAndTariffCodeAndStatus(UUID dependantId, String tariffCode, String status);

    @Query("SELECT EXISTS(SELECT 1 FROM pre_authorizations WHERE auth_number = :authNumber)")
    Mono<Boolean> existsByAuthNumber(String authNumber);
}
