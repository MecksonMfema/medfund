package com.medfund.finance.regulatory.repository;

import com.medfund.finance.regulatory.entity.RegulatorySubmission;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.UUID;

public interface RegulatorySubmissionRepository
        extends ReactiveCrudRepository<RegulatorySubmission, UUID> {

    /** Full supersedes chain for a (tenant, report_key, period), newest first. */
    Flux<RegulatorySubmission> findByTenantIdAndReportKeyAndPeriodStartOrderBySubmissionNumberDesc(
            UUID tenantId, String reportKey, LocalDate periodStart);

    /** Highest existing submission_number for a (tenant, report_key, period). Empty for first submission. */
    @Query("""
            SELECT COALESCE(MAX(submission_number), 0) AS n FROM regulatory_submission
             WHERE tenant_id = :tenantId
               AND report_key = :reportKey
               AND period_start = :periodStart
            """)
    Mono<Integer> maxSubmissionNumber(UUID tenantId, String reportKey, LocalDate periodStart);
}
