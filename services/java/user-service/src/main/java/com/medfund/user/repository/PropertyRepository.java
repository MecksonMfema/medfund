package com.medfund.user.repository;

import com.medfund.user.entity.Property;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface PropertyRepository extends R2dbcRepository<Property, UUID> {

    @Query("SELECT * FROM properties WHERE property_name = :propertyName")
    Mono<Property> findByPropertyName(String propertyName);

    @Query("SELECT EXISTS(SELECT 1 FROM properties WHERE property_name = :propertyName)")
    Mono<Boolean> existsByPropertyName(String propertyName);

    @Query("SELECT * FROM properties WHERE scheme_id = :schemeId ORDER BY property_name")
    Flux<Property> findBySchemeId(UUID schemeId);

    @Query("SELECT * FROM properties WHERE group_id = :groupId ORDER BY property_name")
    Flux<Property> findByGroupId(UUID groupId);

    @Query("SELECT * FROM properties WHERE owner_member_id = :ownerMemberId ORDER BY property_name")
    Flux<Property> findByOwnerMemberId(UUID ownerMemberId);

    @Query("SELECT * FROM properties WHERE status = :status ORDER BY created_at DESC")
    Flux<Property> findByStatus(String status);

    @Query("SELECT * FROM properties ORDER BY created_at DESC")
    Flux<Property> findAllOrderByCreatedAtDesc();

    @Query("""
        SELECT * FROM properties
        WHERE LOWER(property_name) LIKE LOWER(CONCAT('%', :q, '%'))
           OR LOWER(address) LIKE LOWER(CONCAT('%', :q, '%'))
        ORDER BY property_name
        """)
    Flux<Property> search(String q);
}
