package com.medfund.tenancy.dto;

import com.medfund.tenancy.entity.TenantEmailTemplate;

import java.time.Instant;
import java.util.UUID;

/**
 * One row in the tenant's email template list — combines the platform-shipped
 * descriptor (key/name/description/defaults) with the tenant's override state
 * (subject/htmlBody/textBody/enabled). When {@code overridden} is false the
 * tenant has no row in {@code tenant_email_templates} for this key — the
 * platform default is used at send time.
 */
public record TenantEmailTemplateResponse(
        String key,
        String name,
        String description,
        boolean overridden,
        boolean enabled,
        String subject,
        String htmlBody,
        String textBody,
        String defaultSubject,
        String defaultHtmlBody,
        String defaultTextBody,
        UUID id,
        Integer version,
        Instant updatedAt
) {

    /** Used when no override exists — exposes the platform default only. */
    public static TenantEmailTemplateResponse defaultsOnly(EmailTemplateDescriptor d) {
        return new TenantEmailTemplateResponse(
                d.key(), d.name(), d.description(),
                false, true,
                d.defaultSubject(), d.defaultHtmlBody(), d.defaultTextBody(),
                d.defaultSubject(), d.defaultHtmlBody(), d.defaultTextBody(),
                null, null, null
        );
    }

    /** Used when a tenant override row exists — merges descriptor + entity. */
    public static TenantEmailTemplateResponse overridden(EmailTemplateDescriptor d, TenantEmailTemplate t) {
        return new TenantEmailTemplateResponse(
                d.key(), d.name(), d.description(),
                true,
                t.getEnabled() != null ? t.getEnabled() : true,
                t.getSubject(), t.getHtmlBody(), t.getTextBody(),
                d.defaultSubject(), d.defaultHtmlBody(), d.defaultTextBody(),
                t.getId(), t.getVersion(), t.getUpdatedAt()
        );
    }
}
