package com.medfund.claims.siu.repository;

import com.medfund.claims.siu.entity.FraudFlag;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * R2DBC repository for {@link FraudFlag}. All queries are unqualified —
 * per bug_public_prefix_silent_rollback, tenant-schema tables must never
 * carry the {@code public.} prefix in reactive queries.
 */
public interface FraudFlagRepository extends ReactiveCrudRepository<FraudFlag, UUID> {

    Flux<FraudFlag> findAllByClaimIdOrderByFlaggedAtDesc(UUID claimId);

    Flux<FraudFlag> findAllBySiuCaseIdOrderByFlaggedAtDesc(UUID siuCaseId);

    /** Pattern-recognition fact input for FRAUD_TRIAGE (§B Phase 9). */
    @Query("SELECT COUNT(*) FROM fraud_flag " +
           "WHERE claim_id IN (SELECT c.id FROM claims c WHERE c.member_id = :memberId) " +
           "  AND risk_level = 'HIGH' AND flagged_at >= :since")
    Mono<Long> countHighRiskForMemberSince(UUID memberId, OffsetDateTime since);

    /** Pattern-recognition fact input for FRAUD_TRIAGE (§B Phase 9). */
    @Query("SELECT COUNT(*) FROM fraud_flag " +
           "WHERE claim_id IN (SELECT c.id FROM claims c WHERE c.provider_id = :providerId) " +
           "  AND risk_level = 'HIGH' AND flagged_at >= :since")
    Mono<Long> countHighRiskForProviderSince(UUID providerId, OffsetDateTime since);

    /**
     * Nightly purge of unlinked AI flags (see {@code FraudFlagRetentionJob}).
     * Flags linked to an siu_case (siu_case_id IS NOT NULL) are retained
     * under the SIU_CASE_7Y bucket via {@code ReportJobRetentionJob}.
     * Returns rows deleted so the scheduler can log a run summary — the
     * {@code @Modifying} marker is required for R2DBC to surface the
     * generated row count on a DELETE.
     */
    @Modifying
    @Query("DELETE FROM fraud_flag WHERE siu_case_id IS NULL AND flagged_at < :cutoff")
    Mono<Long> purgeUnlinkedOlderThan(OffsetDateTime cutoff);
}
