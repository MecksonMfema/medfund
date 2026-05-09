package com.medfund.claims.repository;

import com.medfund.claims.entity.Drug;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface DrugRepository extends R2dbcRepository<Drug, UUID> {

    @Query("SELECT * FROM drugs ORDER BY drug_name")
    Flux<Drug> findAllOrdered();

    @Query("SELECT * FROM drugs WHERE is_active = TRUE ORDER BY drug_name")
    Flux<Drug> findAllActive();

    @Query("SELECT * FROM drugs WHERE LOWER(drug_name) LIKE LOWER(CONCAT('%', :q, '%')) ORDER BY drug_name LIMIT 50")
    Flux<Drug> searchByName(String q);

    Mono<Drug> findByDrugName(String drugName);
}
