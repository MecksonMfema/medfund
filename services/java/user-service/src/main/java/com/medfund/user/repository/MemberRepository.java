package com.medfund.user.repository;

import com.medfund.user.entity.Member;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface MemberRepository extends R2dbcRepository<Member, UUID> {

    @Query("SELECT * FROM members WHERE member_number = :memberNumber")
    Mono<Member> findByMemberNumber(String memberNumber);

    @Query("SELECT * FROM members WHERE group_id = :groupId ORDER BY last_name, first_name")
    Flux<Member> findByGroupId(UUID groupId);

    @Query("SELECT * FROM members WHERE scheme_id = :schemeId ORDER BY last_name, first_name")
    Flux<Member> findBySchemeId(UUID schemeId);

    @Query("SELECT * FROM members WHERE status = :status ORDER BY created_at DESC")
    Flux<Member> findByStatus(String status);

    @Query("SELECT * FROM members WHERE keycloak_user_id = :keycloakUserId")
    Mono<Member> findByKeycloakUserId(String keycloakUserId);

    @Query("SELECT * FROM members WHERE email = :email")
    Mono<Member> findByEmail(String email);

    @Query("SELECT * FROM members WHERE national_id = :nationalId")
    Mono<Member> findByNationalId(String nationalId);

    @Query("SELECT EXISTS(SELECT 1 FROM members WHERE member_number = :memberNumber)")
    Mono<Boolean> existsByMemberNumber(String memberNumber);

    @Query("SELECT * FROM members WHERE LOWER(first_name) LIKE LOWER(CONCAT('%', :query, '%')) OR LOWER(last_name) LIKE LOWER(CONCAT('%', :query, '%')) OR member_number LIKE CONCAT('%', :query, '%') ORDER BY last_name, first_name")
    Flux<Member> search(String query);

    @Query("SELECT * FROM members ORDER BY created_at DESC")
    Flux<Member> findAllOrderByCreatedAtDesc();

    // ── Cursor pagination ──────────────────────────────────────────────────

    @Query("SELECT * FROM members ORDER BY created_at DESC, id DESC LIMIT :limit")
    Flux<Member> findFirstPage(int limit);

    @Query("""
        SELECT * FROM members
        WHERE created_at < :cursorTs
           OR (created_at = :cursorTs AND id < :cursorId)
        ORDER BY created_at DESC, id DESC
        LIMIT :limit
        """)
    Flux<Member> findNextPage(java.time.Instant cursorTs, UUID cursorId, int limit);

    @Query("SELECT * FROM members WHERE status = :status ORDER BY created_at DESC, id DESC LIMIT :limit")
    Flux<Member> findFirstPageByStatus(String status, int limit);

    @Query("""
        SELECT * FROM members
        WHERE status = :status
          AND (created_at < :cursorTs OR (created_at = :cursorTs AND id < :cursorId))
        ORDER BY created_at DESC, id DESC
        LIMIT :limit
        """)
    Flux<Member> findNextPageByStatus(String status, java.time.Instant cursorTs, UUID cursorId, int limit);

    @Query("""
        SELECT * FROM members
        WHERE (LOWER(first_name) LIKE LOWER(CONCAT('%', :q, '%'))
            OR LOWER(last_name)  LIKE LOWER(CONCAT('%', :q, '%'))
            OR member_number     LIKE CONCAT('%', :q, '%'))
        ORDER BY created_at DESC, id DESC
        LIMIT :limit
        """)
    Flux<Member> searchFirstPage(String q, int limit);

    @Query("""
        SELECT * FROM members
        WHERE (LOWER(first_name) LIKE LOWER(CONCAT('%', :q, '%'))
            OR LOWER(last_name)  LIKE LOWER(CONCAT('%', :q, '%'))
            OR member_number     LIKE CONCAT('%', :q, '%'))
          AND (created_at < :cursorTs OR (created_at = :cursorTs AND id < :cursorId))
        ORDER BY created_at DESC, id DESC
        LIMIT :limit
        """)
    Flux<Member> searchNextPage(String q, java.time.Instant cursorTs, UUID cursorId, int limit);
}
