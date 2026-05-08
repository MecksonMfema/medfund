package com.medfund.user.dto;

import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record UpdateStaffUserRequest(
    @Size(max = 100)
    String firstName,

    @Size(max = 100)
    String lastName,

    @Size(max = 50)
    String phone,

    @Size(max = 100)
    String jobTitle,

    @Size(max = 100)
    String department,

    String realmRole,

    /**
     * When non-null, replace the user's tenant role assignments with this
     * exact set — diffed against the current rows so only the delta is
     * persisted. Empty list strips every role; null leaves them untouched.
     */
    List<UUID> roleIds
) {}
