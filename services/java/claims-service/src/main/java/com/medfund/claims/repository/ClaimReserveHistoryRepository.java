package com.medfund.claims.repository;

import com.medfund.claims.entity.ClaimReserveHistory;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface ClaimReserveHistoryRepository
        extends ReactiveCrudRepository<ClaimReserveHistory, UUID> {

    Flux<ClaimReserveHistory> findByClaimIdOrderByEffectiveAtDesc(UUID claimId);
}
