package com.medfund.user.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Replaces the full permission set of a role atomically. The semantics are
 * "the role now has these permissions and only these" — the service wipes
 * existing rows and re-inserts. Empty list is allowed (role with no
 * permissions = effectively disabled but still assignable).
 *
 * <p>Every key is validated against {@code Permissions.ALL} (the canonical
 * catalogue from {@code permissions.yaml}); unknown keys are rejected with
 * 400 to prevent privilege escalation via fabricated keys.
 */
public record ReplacePermissionsRequest(
        @NotNull
        List<String> permissions
) {}
