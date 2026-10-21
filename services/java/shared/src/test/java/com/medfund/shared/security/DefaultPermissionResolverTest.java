package com.medfund.shared.security;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the V074 compat-mapping in {@link DefaultPermissionResolver}:
 * a role holding only the legacy {@code finance:post_adjustments} gets
 * all three of the new granular {@code finance.notes:*} permissions
 * expanded in on login. This lets the Angular UI (Phase 4) switch its
 * route guards to the new keys without any tenant role-edit on cutover
 * day.
 */
class DefaultPermissionResolverTest {

    @Test
    void applyCompatMappings_legacyPostAdjustments_expandsToThreeNotesPermissions() {
        HashSet<String> input = new HashSet<>(Set.of(Permissions.FINANCE_POST_ADJUSTMENTS));

        HashSet<String> result = DefaultPermissionResolver.applyCompatMappings(input);

        assertThat(result).contains(
                Permissions.FINANCE_POST_ADJUSTMENTS,
                Permissions.FINANCE_NOTES_READ,
                Permissions.FINANCE_NOTES_WRITE,
                Permissions.FINANCE_NOTES_APPROVE);
    }

    @Test
    void applyCompatMappings_noPostAdjustments_leavesSetUntouched() {
        HashSet<String> input = new HashSet<>(Set.of(Permissions.FINANCE_VIEW));

        HashSet<String> result = DefaultPermissionResolver.applyCompatMappings(input);

        assertThat(result).containsExactly(Permissions.FINANCE_VIEW);
    }

    @Test
    void applyCompatMappings_alreadyHasNewKeys_isIdempotent() {
        HashSet<String> input = new HashSet<>(Set.of(
                Permissions.FINANCE_POST_ADJUSTMENTS,
                Permissions.FINANCE_NOTES_READ,
                Permissions.FINANCE_NOTES_WRITE,
                Permissions.FINANCE_NOTES_APPROVE));

        HashSet<String> result = DefaultPermissionResolver.applyCompatMappings(input);

        assertThat(result).hasSize(4);
    }
}
