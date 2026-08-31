package com.medfund.tenancy.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Adds one row to {@code public.tenant_regulatory_recipient}. {@code email}
 * is required; {@code subscribedEventTiers} defaults to all four tiers
 * when null/empty. Uniqueness on {@code (tenant_id, email)} surfaces as
 * HTTP 409.
 */
public record AddTenantRegulatoryRecipientRequest(
        @NotBlank @Email @Size(max = 320) String email,
        @Size(max = 200) String displayName,
        List<String> subscribedEventTiers,
        Boolean isActive
) {}
