package com.medfund.finance.report.repository;

import com.medfund.finance.report.entity.ReportJob;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface ReportJobRepository extends ReactiveCrudRepository<ReportJob, UUID> {

    Flux<ReportJob> findByTenantIdAndParamsHash(UUID tenantId, String paramsHash);

    Mono<ReportJob> findFirstByTenantIdAndParamsHashAndStatusInOrderByRequestedAtDesc(
            UUID tenantId, String paramsHash, List<String> statuses);

    /**
     * Most recent completed IBNR (or LOSS) triangle for the tenant, at or
     * after {@code completedFloor}. Used by
     * {@link com.medfund.finance.ifrs17.service.IbnrSubJobOrchestrator} to
     * decide whether to publish a fresh IBNR sub-job before firing the IFRS 17
     * LIC chunks that consume it.
     */
    Mono<ReportJob> findFirstByTenantIdAndReportKeyAndStatusAndCompletedAtGreaterThanEqualOrderByCompletedAtDesc(
            UUID tenantId, String reportKey, String status, OffsetDateTime completedFloor);
}
