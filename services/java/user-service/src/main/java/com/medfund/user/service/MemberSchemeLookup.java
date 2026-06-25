package com.medfund.user.service;

import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Small read-only helper that returns a member's {@code scheme_id}. Lives
 * outside {@link MemberService} so {@link DependantService} can stay free
 * of the heavier transitive deps (lifecycle service, Keycloak sync, …).
 */
@Service
@RequiredArgsConstructor
public class MemberSchemeLookup {

    private final DatabaseClient db;

    public Mono<UUID> schemeIdOf(UUID memberId) {
        if (memberId == null) return Mono.empty();
        return db.sql("SELECT scheme_id FROM members WHERE id = :id")
                .bind("id", memberId)
                .map(row -> row.get("scheme_id", UUID.class))
                .one();
    }
}
