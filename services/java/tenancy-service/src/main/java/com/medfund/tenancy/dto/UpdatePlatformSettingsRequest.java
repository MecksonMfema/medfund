package com.medfund.tenancy.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

/**
 * Patch payload — null fields are left untouched, non-null fields overwrite.
 * {@code logoUrl} is deliberately absent; logos are uploaded via
 * {@code POST /api/v1/platform/settings/logo}.
 */
public record UpdatePlatformSettingsRequest(
        @Size(max = 200) String platformName,
        @Email @Size(max = 320) String supportEmail,
        @Size(max = 50) String themeTemplateId,
        Boolean darkMode,
        @Size(max = 200) String heroTitle,
        @Size(max = 500) String heroSubtitle
) {}
