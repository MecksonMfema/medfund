package com.medfund.finance.actuarial.repository;

import com.medfund.finance.actuarial.entity.ActuarialReportJob;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface ActuarialReportJobRepository
        extends ReactiveCrudRepository<ActuarialReportJob, UUID> {

    Flux<ActuarialReportJob> findByTenantIdAndParamsHash(UUID tenantId, String paramsHash);

    Mono<ActuarialReportJob> findFirstByTenantIdAndParamsHashAndStatusInOrderByRequestedAtDesc(
            UUID tenantId, String paramsHash, java.util.List<String> statuses);
}
