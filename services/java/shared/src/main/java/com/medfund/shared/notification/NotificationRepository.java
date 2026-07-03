package com.medfund.shared.notification;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

/**
 * Reactive access layer for {@code public.notifications}.
 * The bell dropdown queries {@link #findRecentForUser(UUID, int)}; the
 * badge count uses {@link #countUnseenForUser(UUID)}; {@link
 * #markAllSeenForUser(UUID, Instant)} handles the "Mark all read" click.
 */
public interface NotificationRepository extends ReactiveCrudRepository<Notification, UUID> {

    @Query("""
        SELECT * FROM notifications
         WHERE user_id = :userId
         ORDER BY created_at DESC
         LIMIT :limit
        """)
    Flux<Notification> findRecentForUser(UUID userId, int limit);

    @Query("""
        SELECT COUNT(*) FROM notifications
         WHERE user_id = :userId AND seen_at IS NULL
        """)
    Mono<Long> countUnseenForUser(UUID userId);

    @Query("""
        UPDATE notifications
           SET seen_at = :seenAt
         WHERE user_id = :userId AND seen_at IS NULL
        """)
    Mono<Integer> markAllSeenForUser(UUID userId, Instant seenAt);

    @Query("""
        UPDATE notifications
           SET seen_at = :seenAt
         WHERE id = :id AND user_id = :userId AND seen_at IS NULL
        """)
    Mono<Integer> markSeen(UUID id, UUID userId, Instant seenAt);

    /**
     * De-duplication helper. A producer that might fire the same domain
     * event more than once (e.g. a Kafka retry) can call this first and
     * skip the insert if a row already exists for the {source_type,
     * source_id, user_id} tuple.
     */
    @Query("""
        SELECT * FROM notifications
         WHERE user_id = :userId
           AND source_type = :sourceType
           AND source_id = :sourceId
         LIMIT 1
        """)
    Mono<Notification> findBySource(UUID userId, String sourceType, UUID sourceId);
}
