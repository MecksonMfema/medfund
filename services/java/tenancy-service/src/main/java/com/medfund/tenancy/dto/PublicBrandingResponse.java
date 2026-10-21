package com.medfund.tenancy.dto;

import com.medfund.tenancy.entity.PlatformSettings;

/**
 * Unauthenticated slice of platform settings. Consumed by the Angular
 * pre-auth surface to skin the login screen with platform branding before a
 * JWT is available. Deliberately excludes {@code supportEmail} and the raw
 * audit metadata — those are internal.
 */
public record PublicBrandingResponse(
        String platformName,
        String logoUrl,
        String themeTemplateId,
        Boolean darkMode,
        String heroTitle,
        String heroSubtitle
) {
    public static PublicBrandingResponse from(PlatformSettings e) {
        return new PublicBrandingResponse(
                e.getPlatformName(),
                e.logoPath(),
                e.getThemeTemplateId(),
                e.getDarkMode(),
                e.getHeroTitle(),
                e.getHeroSubtitle()
        );
    }
}
