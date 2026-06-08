package com.medfund.user.repository;

import com.medfund.user.entity.GroupLiaison;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface GroupLiaisonRepository extends R2dbcRepository<GroupLiaison, UUID> {

    @Query("SELECT EXISTS(SELECT 1 FROM group_liaisons WHERE LOWER(email) = LOWER(:email))")
    Mono<Boolean> existsByEmailIgnoreCase(String email);

    @Query("""
        SELECT * FROM group_liaisons
        WHERE LOWER(first_name) LIKE LOWER(CONCAT('%', :q, '%'))
           OR LOWER(last_name)  LIKE LOWER(CONCAT('%', :q, '%'))
           OR LOWER(email)      LIKE LOWER(CONCAT('%', :q, '%'))
        ORDER BY last_name, first_name
        LIMIT :limit
        """)
    Flux<GroupLiaison> search(String q, int limit);

    @Query("SELECT * FROM group_liaisons ORDER BY created_at DESC")
    Flux<GroupLiaison> findAllOrderByCreatedAtDesc();
}
