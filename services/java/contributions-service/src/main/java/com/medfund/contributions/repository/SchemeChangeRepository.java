package com.medfund.contributions.repository;

import com.medfund.contributions.entity.SchemeChange;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.UUID;

public interface SchemeChangeRepository extends R2dbcRepository<SchemeChange, UUID> {

    @Query("SELECT * FROM scheme_changes WHERE member_id = :memberId ORDER BY created_at DESC")
    Flux<SchemeChange> findByMemberId(UUID memberId);

    @Query("SELECT * FROM scheme_changes WHERE status = :status ORDER BY created_at DESC")
    Flux<SchemeChange> findByStatus(String status);

    @Query("SELECT * FROM scheme_changes WHERE member_id = :memberId AND status = :status")
    Mono<SchemeChange> findByMemberIdAndStatus(UUID memberId, String status);

    /**
     * Approved rows whose effective_date has arrived. Driven by
     * {@code SchemeChangeExecutor} on the daily SCHEME_CHANGE_ROLL sweep.
     */
    @Query("SELECT * FROM scheme_changes WHERE status = 'APPROVED' AND effective_date <= :today ORDER BY effective_date")
    Flux<SchemeChange> findReadyToApply(LocalDate today);
}
