package com.medfund.claims.siu.repository;

import com.medfund.claims.siu.entity.SiuEvidence;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface SiuEvidenceRepository extends ReactiveCrudRepository<SiuEvidence, UUID> {
    Flux<SiuEvidence> findAllByCaseIdOrderByUploadedAtDesc(UUID caseId);
}
