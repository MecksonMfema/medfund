package com.medfund.tenancy.entity;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@Table("platform_settings")
public class PlatformSettings {

    @Id
    private UUID id;

    private Boolean singleton;
    private String platformName;
    private String supportEmail;

    private byte[] logoBytes;
    private String logoMime;

    private String themeTemplateId;
    private Boolean darkMode;
    private String heroTitle;
    private String heroSubtitle;

    @Column("updated_at")
    private OffsetDateTime updatedAt;

    @Column("updated_by")
    private String updatedBy;

    private Long version;

    /**
     * Relative URL of the public logo endpoint, cache-busted with
     * {@code updatedAt} so a fresh upload invalidates the client cache.
     * Returns null when no logo has been uploaded. Single source for the
     * response DTOs and the Keycloak realm-branding sync.
     */
    public String logoPath() {
        if (logoBytes == null || logoBytes.length == 0) {
            return null;
        }
        return "/api/v1/public/platform/logo?v="
                + (updatedAt != null ? updatedAt.toEpochSecond() : 0);
    }
}
