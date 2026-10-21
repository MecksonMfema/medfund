package com.medfund.claims.siu.repository;

import com.medfund.claims.siu.entity.SiuCaseNote;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface SiuCaseNoteRepository extends ReactiveCrudRepository<SiuCaseNote, UUID> {

    /** Chronological (oldest → newest) — feeds the activity-timeline widget. */
    Flux<SiuCaseNote> findAllByCaseIdOrderByCreatedAtAsc(UUID caseId);
}
