package com.medfund.tenancy.dto;

/**
 * Catalogue entry for a single email template the platform supports. Tenants
 * see this list as the set of overridable templates — the {@code key} is the
 * canonical identifier notification-service uses when sending. The
 * {@code defaultSubject} / {@code defaultHtmlBody} are the platform-shipped
 * starting points; an admin's customised override (if any) is layered on top
 * at send time.
 */
public record EmailTemplateDescriptor(
        String key,
        String name,
        String description,
        String defaultSubject,
        String defaultHtmlBody,
        String defaultTextBody
) {}
