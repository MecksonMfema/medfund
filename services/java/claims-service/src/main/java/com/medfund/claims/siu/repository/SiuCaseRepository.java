package com.medfund.claims.siu.repository;

import com.medfund.claims.siu.entity.SiuCase;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface SiuCaseRepository extends ReactiveCrudRepository<SiuCase, UUID> {

    Flux<SiuCase> findAllByStatusOrderByOpenedAtDesc(String status);

    Flux<SiuCase> findAllByAssignedToOrderByOpenedAtDesc(UUID assignedTo);
}
