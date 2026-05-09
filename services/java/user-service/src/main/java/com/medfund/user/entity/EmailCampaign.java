package com.medfund.user.entity;

import io.r2dbc.postgresql.codec.Json;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Tenant-composed outbound email. Drafts hold subject, body, and an audience
 * filter (JSONB); status flips to 'sent' once dispatched. Actual SMTP delivery
 * happens out-of-band — this row is the audit trail.
 */
@Getter
@Setter
@NoArgsConstructor
@Table("email_campaigns")
public class EmailCampaign {

    @Id
    private UUID id;

    @Column("sender_id")
    private UUID senderId;

    private String subject;

    @Column("body_html")
    private String bodyHtml;

    @Column("body_text")
    private String bodyText;

    @Column("audience_filter")
    private Json audienceFilter;

    private String status;

    @Column("scheduled_for")
    private Instant scheduledFor;

    @Column("sent_at")
    private Instant sentAt;

    @Column("recipient_count")
    private Integer recipientCount;

    @CreatedDate
    @Column("created_at")
    private Instant createdAt;

    @LastModifiedDate
    @Column("updated_at")
    private Instant updatedAt;

    @Column("created_by")
    private UUID createdBy;

    @Column("updated_by")
    private UUID updatedBy;
}
