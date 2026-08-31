package com.medfund.tenancy.dto;

import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Mutates a subset of the recipient row. {@code email} is immutable —
 * changing the address is a delete + add. Null fields leave the current
 * value in place.
 */
public record UpdateTenantRegulatoryRecipientRequest(
        @Size(max = 200) String displayName,
        List<String> subscribedEventTiers,
        Boolean isActive
) {}
