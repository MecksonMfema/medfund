package com.medfund.user.entity;

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
 * A tenant-registered outbound email address (e.g. billing@acme.health).
 * Notification-service consults this table to choose the From address for
 * outbound campaigns; only rows with status = 'verified' are usable.
 */
@Getter
@Setter
@NoArgsConstructor
@Table("email_senders")
public class EmailSender {

    @Id
    private UUID id;

    private String address;

    @Column("display_name")
    private String displayName;

    /** pending | verified | revoked. */
    private String status;

    @Column("verified_at")
    private Instant verifiedAt;

    private String notes;

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
