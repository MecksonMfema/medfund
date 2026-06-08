package com.medfund.contributions.dto;

import com.medfund.contributions.entity.Group;

import java.util.UUID;

/**
 * Slim projection used by the Group Charge autocomplete and any future
 * group-picker. The group's liaison record (resolved via liaison_kind +
 * liaison_user_id) is the canonical source of contact info, so this
 * projection only carries the group's own identity.
 */
public record GroupOption(
        UUID id,
        String name,
        String registrationNumber
) {
    public static GroupOption from(Group g) {
        return new GroupOption(g.getId(), g.getName(), g.getRegistrationNumber());
    }
}
