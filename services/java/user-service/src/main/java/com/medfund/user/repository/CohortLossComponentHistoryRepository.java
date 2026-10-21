package com.medfund.user.repository;

import com.medfund.user.entity.CohortLossComponentHistory;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface CohortLossComponentHistoryRepository extends R2dbcRepository<CohortLossComponentHistory, UUID> {

    @Query("""
            SELECT * FROM cohort_loss_component_history
            WHERE cohort_id = :cohortId
            ORDER BY effective_at DESC, created_at DESC
            """)
    Flux<CohortLossComponentHistory> findByCohortIdOrderByEffectiveAtDesc(UUID cohortId);
}
