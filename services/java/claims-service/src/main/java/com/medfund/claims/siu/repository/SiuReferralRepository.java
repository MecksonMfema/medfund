package com.medfund.claims.siu.repository;

import com.medfund.claims.siu.entity.SiuReferral;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface SiuReferralRepository extends ReactiveCrudRepository<SiuReferral, UUID> {
    Flux<SiuReferral> findAllByCaseIdOrderByReferredAtDesc(UUID caseId);
}
