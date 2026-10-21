package com.medfund.tenancy.entity;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;

/**
 * Implements Persistable so R2DBC's save() dispatches to INSERT even though
 * the @Id (the enum name) is always pre-populated. Without this the seeder
 * would trip the "Row with Id X does not exist" UPDATE-mode failure
 * documented in bug_r2dbc_pre_populated_id_update_mode. The seeder flips the
 * transient {@code newRow} flag before the first save; subsequent updates
 * clear it via {@link #markAsPersisted()}.
 */
@Getter
@Setter
@Table("platform_feature_flags")
public class PlatformFeatureFlag implements Persistable<String> {

    @Id
    private String key;

    private Boolean enabled;
    private OffsetDateTime updatedAt;
    private String updatedBy;
    private Long version;

    @Transient
    private boolean newRow;

    @Override
    public String getId() { return key; }

    @Override
    public boolean isNew() { return newRow; }

    public void markAsNew() { this.newRow = true; }
    public void markAsPersisted() { this.newRow = false; }
}
