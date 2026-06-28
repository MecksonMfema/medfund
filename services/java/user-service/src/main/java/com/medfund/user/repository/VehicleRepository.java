package com.medfund.user.repository;

import com.medfund.user.entity.Vehicle;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface VehicleRepository extends R2dbcRepository<Vehicle, UUID> {

    @Query("SELECT * FROM vehicles WHERE registration_number = :registrationNumber")
    Mono<Vehicle> findByRegistrationNumber(String registrationNumber);

    @Query("SELECT EXISTS(SELECT 1 FROM vehicles WHERE registration_number = :registrationNumber)")
    Mono<Boolean> existsByRegistrationNumber(String registrationNumber);

    @Query("SELECT * FROM vehicles WHERE scheme_id = :schemeId ORDER BY registration_number")
    Flux<Vehicle> findBySchemeId(UUID schemeId);

    @Query("SELECT * FROM vehicles WHERE group_id = :groupId ORDER BY registration_number")
    Flux<Vehicle> findByGroupId(UUID groupId);

    @Query("SELECT * FROM vehicles WHERE owner_member_id = :ownerMemberId ORDER BY registration_number")
    Flux<Vehicle> findByOwnerMemberId(UUID ownerMemberId);

    @Query("SELECT * FROM vehicles WHERE status = :status ORDER BY created_at DESC")
    Flux<Vehicle> findByStatus(String status);

    @Query("SELECT * FROM vehicles ORDER BY created_at DESC")
    Flux<Vehicle> findAllOrderByCreatedAtDesc();

    @Query("""
        SELECT * FROM vehicles
        WHERE LOWER(registration_number) LIKE LOWER(CONCAT('%', :q, '%'))
           OR LOWER(make) LIKE LOWER(CONCAT('%', :q, '%'))
           OR LOWER(model) LIKE LOWER(CONCAT('%', :q, '%'))
        ORDER BY registration_number
        """)
    Flux<Vehicle> search(String q);
}
