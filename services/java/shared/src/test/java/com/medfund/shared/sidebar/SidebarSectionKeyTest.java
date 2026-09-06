package com.medfund.shared.sidebar;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class SidebarSectionKeyTest {

    @Test
    void parse_matchesUpperCaseName() {
        assertThat(SidebarSectionKey.parse("BILLING_TRANSACTIONS"))
                .hasValueSatisfying(k -> assertThat(k).isEqualTo(SidebarSectionKey.BILLING_TRANSACTIONS));
    }

    @Test
    void parse_isCaseInsensitive() {
        assertThat(SidebarSectionKey.parse("billing_transactions"))
                .hasValueSatisfying(k -> assertThat(k).isEqualTo(SidebarSectionKey.BILLING_TRANSACTIONS));
    }

    @Test
    void parse_returnsEmptyOnUnknown() {
        assertThat(SidebarSectionKey.parse("UNKNOWN_KEY")).isEmpty();
    }

    @Test
    void parse_returnsEmptyOnNull() {
        assertThat(SidebarSectionKey.parse(null)).isEmpty();
    }

    @Test
    void key_matchesEnumName() {
        Stream.of(SidebarSectionKey.values()).forEach(k ->
                assertThat(k.key()).isEqualTo(k.name()));
    }

    @Test
    void labelAndGroupAreNeverBlank() {
        Stream.of(SidebarSectionKey.values()).forEach(k -> {
            assertThat(k.getLabel()).isNotBlank();
            assertThat(k.getGroup()).isNotNull();
        });
    }

    @Test
    void everySectionKeyIsUnique() {
        Set<String> names = Stream.of(SidebarSectionKey.values())
                .map(Enum::name)
                .collect(java.util.stream.Collectors.toSet());
        assertThat(names).hasSameSizeAs(SidebarSectionKey.values());
    }

    @Test
    void overviewGroupHasNoKeys() {
        // Overview / Dashboard is always visible - no admin toggle. Guard
        // that no future addition drags Overview into the togglable set,
        // which would let an admin lock themselves out of the portal.
        boolean anyInOverview = Stream.of(SidebarSectionKey.values())
                .anyMatch(k -> k.getGroup() == SidebarGroup.OVERVIEW);
        assertThat(anyInOverview).isFalse();
    }

    @Test
    void allNonOverviewGroupsHaveAtLeastOneKey() {
        Set<SidebarGroup> covered = Stream.of(SidebarSectionKey.values())
                .map(SidebarSectionKey::getGroup)
                .collect(java.util.stream.Collectors.toSet());
        for (SidebarGroup g : SidebarGroup.values()) {
            if (g == SidebarGroup.OVERVIEW) continue;
            assertThat(covered)
                    .as("group %s should have at least one togglable item", g)
                    .contains(g);
        }
    }
}
