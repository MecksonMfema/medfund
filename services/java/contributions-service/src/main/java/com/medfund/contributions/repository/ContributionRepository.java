package com.medfund.contributions.repository;

import com.medfund.contributions.entity.Contribution;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.UUID;

public interface ContributionRepository extends R2dbcRepository<Contribution, UUID> {

    @Query("SELECT * FROM contributions WHERE member_id = :memberId ORDER BY period_start DESC")
    Flux<Contribution> findByMemberId(UUID memberId);

    @Query("SELECT * FROM contributions WHERE group_id = :groupId ORDER BY period_start DESC")
    Flux<Contribution> findByGroupId(UUID groupId);

    @Query("SELECT * FROM contributions WHERE scheme_id = :schemeId ORDER BY period_start DESC")
    Flux<Contribution> findBySchemeId(UUID schemeId);

    @Query("SELECT * FROM contributions WHERE status = :status ORDER BY created_at DESC")
    Flux<Contribution> findByStatus(String status);

    @Query("SELECT * FROM contributions WHERE group_id = :groupId AND period_start = :start AND period_end = :end")
    Flux<Contribution> findByGroupIdAndPeriodStartAndPeriodEnd(UUID groupId, LocalDate start, LocalDate end);

    @Query("SELECT * FROM contributions WHERE member_id = :memberId AND status = :status")
    Flux<Contribution> findByMemberIdAndStatus(UUID memberId, String status);

    /**
     * Period-guard: how many contribution rows already exist for this
     * (period, insurance line) pair. Used by BillingService.commitBilling
     * to block a second commit for the same month — the cooldown timer
     * only protects against accidental double-clicks; this stops a
     * deliberate re-commit after hours have passed.
     *
     * <p>When {@code insuranceLine} is null the count is across every
     * scheme (legacy single-line behaviour). When non-null it scopes
     * to schemes on that line so multi-line tenants commit each line
     * independently — committing MOTOR for July doesn't block HEALTH
     * for July.
     */
    @Query("""
            SELECT COUNT(*) FROM contributions c
             WHERE c.period_start = :start
               AND c.period_end   = :end
               AND ( :insuranceLine IS NULL
                  OR EXISTS (SELECT 1 FROM schemes s
                              WHERE s.id = c.scheme_id
                                AND s.insurance_line = :insuranceLine) )
            """)
    Mono<Long> countByPeriodAndLine(LocalDate start, LocalDate end, String insuranceLine);
}
