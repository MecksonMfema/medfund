package com.medfund.user.repository;

import com.medfund.user.entity.MemberDependantSwap;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.UUID;

public interface MemberDependantSwapRepository extends ReactiveCrudRepository<MemberDependantSwap, UUID> {

    Flux<MemberDependantSwap> findByOldMemberId(UUID oldMemberId);

    Flux<MemberDependantSwap> findByDependantId(UUID dependantId);

    Flux<MemberDependantSwap> findByStatus(String status);

    /** Sweep target for ScheduledStatusExecutor — APPROVED rows whose effective_date has arrived. */
    @Query("SELECT * FROM member_dependant_swaps WHERE status = 'APPROVED' AND effective_date <= :today")
    Flux<MemberDependantSwap> findReadyToApply(LocalDate today);

    /** Live-swap guard: only one PENDING/APPROVED swap per (member, dependant) pair. */
    @Query("""
            SELECT COUNT(*) FROM member_dependant_swaps
             WHERE old_member_id = :oldMemberId AND dependant_id = :dependantId
               AND status IN ('PENDING','APPROVED')
            """)
    Mono<Long> countLive(UUID oldMemberId, UUID dependantId);
}
