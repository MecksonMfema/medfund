package com.medfund.user.repository;

import com.medfund.user.entity.Group;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface GroupRepository extends R2dbcRepository<Group, UUID> {

    @Query("SELECT * FROM groups WHERE status = :status ORDER BY created_at DESC")
    Flux<Group> findByStatus(String status);

    /** V043 — arrears-suspended lookup used for auto-reactivation. */
    @Query("SELECT * FROM groups WHERE status = 'suspended' AND suspend_reason = :reason")
    Flux<Group> findSuspendedByReason(String reason);

    @Query("SELECT * FROM groups ORDER BY created_at DESC")
    Flux<Group> findAllOrderByCreatedAtDesc();

    @Query("SELECT * FROM groups WHERE registration_number = :registrationNumber")
    Mono<Group> findByRegistrationNumber(String registrationNumber);

    @Query("SELECT * FROM groups WHERE LOWER(name) LIKE LOWER(CONCAT('%', :name, '%')) ORDER BY name")
    Flux<Group> searchByName(String name);
}
