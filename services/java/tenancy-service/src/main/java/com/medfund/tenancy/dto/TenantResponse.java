package com.medfund.tenancy.dto;

import com.medfund.tenancy.entity.Tenant;

import java.time.Instant;
import java.util.UUID;

public record TenantResponse(
        UUID id,
        String name,
        String slug,
        String domain,
        String schemaName,
        UUID planId,
        String status,
        String settings,
        String branding,
        String contactEmail,
        String countryCode,
        String timezone,
        String membershipModel,
        /** STANDARD or INDIVIDUAL — see V118. */
        String pricingModel,
        /** INDEPENDENT or SHARED_WITH_SUFFIX — see V120. */
        String memberNumberScheme,
        String keycloakRealm,
        Instant createdAt,
        Instant updatedAt
) {
    public static TenantResponse from(Tenant tenant) {
        return new TenantResponse(
                tenant.getId(),
                tenant.getName(),
                tenant.getSlug(),
                tenant.getDomain(),
                tenant.getSchemaName(),
                tenant.getPlanId(),
                tenant.getStatus(),
                tenant.getSettings() != null ? tenant.getSettings().value() : "{}",
                tenant.getBranding() != null ? tenant.getBranding().value() : "{}",
                tenant.getContactEmail(),
                tenant.getCountryCode(),
                tenant.getTimezone(),
                tenant.getMembershipModel(),
                tenant.getPricingModel() != null ? tenant.getPricingModel() : "STANDARD",
                tenant.getMemberNumberScheme() != null ? tenant.getMemberNumberScheme() : "INDEPENDENT",
                tenant.getKeycloakRealm(),
                tenant.getCreatedAt(),
                tenant.getUpdatedAt()
        );
    }
}
