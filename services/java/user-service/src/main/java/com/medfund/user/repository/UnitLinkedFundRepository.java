package com.medfund.user.repository;

import com.medfund.user.entity.UnitLinkedFund;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface UnitLinkedFundRepository extends R2dbcRepository<UnitLinkedFund, UUID> {

    @Query("""
            SELECT * FROM unit_linked_fund
            ORDER BY is_active DESC, name ASC
            """)
    Flux<UnitLinkedFund> findAllOrdered();

    @Query("""
            SELECT * FROM unit_linked_fund
            WHERE is_active = true
            ORDER BY name ASC
            """)
    Flux<UnitLinkedFund> findActive();
}
