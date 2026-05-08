package com.medfund.contributions.dto;

import com.medfund.contributions.entity.Group;

import java.util.UUID;

/**
 * Slim projection used by the Group Charge autocomplete and any future
 * group-picker. Carries enough metadata for the UI to render a label like
 * "Acme Corp · ACME001" without exposing every column on the groups row.
 */
public record GroupOption(
        UUID id,
        String name,
        String registrationNumber,
        String contactEmail
) {
    public static GroupOption from(Group g) {
        return new GroupOption(g.getId(), g.getName(), g.getRegistrationNumber(), g.getContactEmail());
    }
}
