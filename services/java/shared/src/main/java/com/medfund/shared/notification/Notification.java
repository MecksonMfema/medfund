package com.medfund.shared.notification;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * R2DBC row for {@code public.notifications}. See V122 for column
 * semantics — this class is a straight mirror of the DDL. Kept in
 * shared so any Java service that needs to read or write in-app
 * notifications can depend on it without introducing per-service
 * copies of the type.
 *
 * <p>Uses {@code @Getter @Setter} on purpose (not {@code @Data}) so
 * lazy-loading + equals/hashCode behave predictably; the Lombok rule
 * for entities in CLAUDE.md applies.
 */
@Getter
@Setter
@Table("notifications")
public class Notification {
    @Id
    private UUID id;

    @Column("tenant_id")
    private UUID tenantId;

    @Column("user_id")
    private UUID userId;

    private String kind;
    private String title;
    private String body;
    private String severity;

    @Column("source_type")
    private String sourceType;

    @Column("source_id")
    private UUID sourceId;

    @Column("action_url")
    private String actionUrl;

    /** Producer-shaped JSON blob; the bell doesn't parse it. */
    private String metadata;

    @Column("created_at")
    private Instant createdAt;

    @Column("seen_at")
    private Instant seenAt;
}
