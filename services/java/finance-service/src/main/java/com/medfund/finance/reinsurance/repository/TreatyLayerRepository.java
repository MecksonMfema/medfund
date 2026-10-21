package com.medfund.finance.reinsurance.repository;

import com.medfund.finance.reinsurance.entity.TreatyLayer;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface TreatyLayerRepository extends R2dbcRepository<TreatyLayer, UUID> {

    @Query("SELECT * FROM treaty_layer WHERE treaty_id = :treatyId ORDER BY layer_order")
    Flux<TreatyLayer> findByTreatyIdOrderByLayerOrder(UUID treatyId);

    @Query("SELECT COUNT(*) FROM treaty_layer WHERE treaty_id = :treatyId")
    Mono<Long> countByTreatyId(UUID treatyId);
}
