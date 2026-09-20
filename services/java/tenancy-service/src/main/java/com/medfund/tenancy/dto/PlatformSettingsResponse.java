package com.medfund.tenancy.dto;

import com.medfund.tenancy.entity.PlatformSettings;

import java.time.OffsetDateTime;

/**
 * Full platform settings for the super-admin portal. Excludes the raw logo
 * bytes; {@code logoUrl} points at {@code /api/v1/public/platform/logo} which
 * streams the bytes on demand. Cache-busted with an {@code updatedAt} query
 * param so a fresh upload invalidates the client cache.
 */
public record PlatformSettingsResponse(
        String platformName,
        String supportEmail,
        String logoUrl,
        String themeTemplateId,
        Boolean darkMode,
        String heroTitle,
        String heroSubtitle,
        OffsetDateTime updatedAt,
        String updatedBy
) {
    public static PlatformSettingsResponse from(PlatformSettings e) {
        return new PlatformSettingsResponse(
                e.getPlatformName(),
                e.getSupportEmail(),
                e.logoPath(),
                e.getThemeTemplateId(),
                e.getDarkMode(),
                e.getHeroTitle(),
                e.getHeroSubtitle(),
                e.getUpdatedAt(),
                e.getUpdatedBy()
        );
    }
}
