package com.medfund.user.dto;

import com.medfund.user.entity.Provider;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ProviderResponse(
    UUID id,
    String name,
    String providerType,
    String registrationNumber,
    String specialty,
    String email,
    String phone,
    String city,
    String address,
    String bankingDetails,
    String keycloakUserId,
    String status,
    String networkTier,
    Instant createdAt,
    Instant updatedAt,
    /**
     * Tenants this provider is contracted with (public.provider_tenants) and
     * the insurance lines it is tagged for (public.provider_insurance_lines).
     * Populated on the paginated list so the admin console can render both
     * pill columns without a request per row; null on the single-provider
     * reads, which have no pill column to fill.
     */
    List<UUID> tenantIds,
    List<String> insuranceLines
) {
    public static ProviderResponse from(Provider p) {
        return from(p, null, null);
    }

    public static ProviderResponse from(Provider p, List<UUID> tenantIds, List<String> insuranceLines) {
        return new ProviderResponse(
            p.getId(), p.getName(), p.getProviderType(),
            p.getRegistrationNumber(),
            p.getSpecialty(), p.getEmail(), p.getPhone(), p.getCity(), p.getAddress(),
            p.getBankingDetails(), p.getKeycloakUserId(), p.getStatus(),
            p.getNetworkTier(),
            p.getCreatedAt(), p.getUpdatedAt(),
            tenantIds, insuranceLines
        );
    }
}
