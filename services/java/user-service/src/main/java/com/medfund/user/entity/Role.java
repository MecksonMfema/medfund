package com.medfund.user.entity;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

@Table("roles")
public class Role {

    @Id
    private UUID id;

    private String name;

    @Column("display_name")
    private String displayName;

    private String description;

    @Column("is_system")
    private Boolean isSystem;

    /**
     * Stable Keycloak realm-role identifier — set on creation and never updated
     * even if {@code displayName} or {@code description} change. Reserved for
     * future Keycloak realm-role mirroring of tenant-defined roles. Today the
     * platform resolves permissions server-side from the DB, so this column
     * is only populated for the seeded {@code tenant_admin} role.
     */
    @Column("keycloak_role_name")
    private String keycloakRoleName;

    @CreatedDate
    @Column("created_at")
    private Instant createdAt;

    @LastModifiedDate
    @Column("updated_at")
    private Instant updatedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Boolean getIsSystem() { return isSystem; }
    public void setIsSystem(Boolean isSystem) { this.isSystem = isSystem; }

    public String getKeycloakRoleName() { return keycloakRoleName; }
    public void setKeycloakRoleName(String keycloakRoleName) { this.keycloakRoleName = keycloakRoleName; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
