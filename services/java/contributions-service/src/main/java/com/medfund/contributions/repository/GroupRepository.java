package com.medfund.contributions.repository;

import com.medfund.contributions.entity.Group;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface GroupRepository extends R2dbcRepository<Group, UUID> {

    @Query("""
        SELECT * FROM groups
         WHERE status = 'active'
           AND (LOWER(name) LIKE :q
                OR LOWER(COALESCE(registration_number, '')) LIKE :q
                OR LOWER(COALESCE(contact_email, '')) LIKE :q)
         ORDER BY name
         LIMIT :limit
        """)
    Flux<Group> searchActive(String q, int limit);

    @Query("SELECT * FROM groups WHERE status = 'active' ORDER BY name LIMIT :limit")
    Flux<Group> listActive(int limit);
}
