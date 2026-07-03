package com.medfund.user.entity;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

@Table("groups")
public class Group {

    @Id
    private UUID id;

    private String name;

    @Column("registration_number")
    private String registrationNumber;

    private String address;

    /**
     * Group-level email — the fallback recipient when no liaison is assigned.
     * Required for new groups at the DTO layer (@NotBlank @Email on
     * {@code CreateGroupRequest}); left nullable at the DB level so V038
     * doesn't wedge on pre-existing rows. See recipient.ForGroup in the
     * notification-service which resolves to this address when
     * {@code liaison_user_id IS NULL}.
     */
    private String email;

    /**
     * Discriminator for the liaison_user_id FK target. NULL when no liaison
     * is assigned; otherwise one of 'MEMBER' (FK to {@code members.id}) or
     * 'STAFF' (FK to {@code staff_users.id}). Pair-consistency is enforced
     * by a CHECK constraint at the schema level (V023).
     */
    @Column("liaison_kind")
    private String liaisonKind;

    @Column("liaison_user_id")
    private UUID liaisonUserId;

    private String status;

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

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getRegistrationNumber() { return registrationNumber; }
    public void setRegistrationNumber(String registrationNumber) { this.registrationNumber = registrationNumber; }

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getLiaisonKind() { return liaisonKind; }
    public void setLiaisonKind(String liaisonKind) { this.liaisonKind = liaisonKind; }

    public UUID getLiaisonUserId() { return liaisonUserId; }
    public void setLiaisonUserId(UUID liaisonUserId) { this.liaisonUserId = liaisonUserId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }

    public UUID getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(UUID updatedBy) { this.updatedBy = updatedBy; }
}
