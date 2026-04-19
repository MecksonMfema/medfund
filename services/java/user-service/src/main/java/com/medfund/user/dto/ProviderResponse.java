package com.medfund.user.dto;

import com.medfund.user.entity.Provider;

import java.time.Instant;
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
    Instant createdAt,
    Instant updatedAt
) {
    public static ProviderResponse from(Provider p) {
        return new ProviderResponse(
            p.getId(), p.getName(), p.getProviderType(),
            p.getRegistrationNumber(),
            p.getSpecialty(), p.getEmail(), p.getPhone(), p.getCity(), p.getAddress(),
            p.getBankingDetails(), p.getKeycloakUserId(), p.getStatus(),
            p.getCreatedAt(), p.getUpdatedAt()
        );
    }
}
