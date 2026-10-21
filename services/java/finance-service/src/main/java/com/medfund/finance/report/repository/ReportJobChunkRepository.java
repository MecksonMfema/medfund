package com.medfund.finance.report.repository;

import com.medfund.finance.report.entity.ReportJobChunk;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface ReportJobChunkRepository extends ReactiveCrudRepository<ReportJobChunk, UUID> {

    Flux<ReportJobChunk> findByParentJobId(UUID parentJobId);

    Flux<ReportJobChunk> findByParentJobIdAndStatus(UUID parentJobId, String status);
}
