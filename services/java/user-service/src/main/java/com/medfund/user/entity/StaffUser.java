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
 * Platform-level staff user. Stored in the public schema and synced to the
 * medfund-platform Keycloak realm. Distinct from Members/Providers which are
 * tenant-scoped insurance participants.
 */
@Getter
@Setter
@NoArgsConstructor
@Table(schema = "public", value = "staff_users")
public class StaffUser {

    @Id
    private UUID id;

    @Column("first_name")  private String firstName;
    @Column("last_name")   private String lastName;

    private String email;
    private String phone;

    @Column("job_title")        private String jobTitle;
    private String department;
    @Column("realm_role")       private String realmRole;
    @Column("keycloak_user_id") private String keycloakUserId;
    /** Null for platform super_admin users; non-null for all tenant-scoped staff. */
    @Column("tenant_id")        private UUID tenantId;

    private String status;

    /**
     * Timestamp the most recent invite email was sent. The Keycloak link
     * generated alongside it expires {@link com.medfund.user.service.StaffUserService#INVITE_TTL}
     * after this point. Cleared (or simply ignored) once status flips to 'active'.
     */
    @Column("invited_at") private Instant invitedAt;

    @CreatedDate  @Column("created_at") private Instant createdAt;
    @LastModifiedDate @Column("updated_at") private Instant updatedAt;

    @Column("created_by") private UUID createdBy;
    @Column("updated_by") private UUID updatedBy;
}
