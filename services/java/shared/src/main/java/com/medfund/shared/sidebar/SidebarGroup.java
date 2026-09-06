package com.medfund.shared.sidebar;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Top-level grouping for operations-portal sidebar items. Mirrors the
 * eight section headings the Angular {@code OPERATIONAL_NAV} config
 * ships, so the tenant-admin visibility grid can bucket toggles under
 * the same headings the user sees in their sidebar.
 *
 * <p>Order below is the render order in both the sidebar itself and
 * the tenant-admin settings grid. {@link #OVERVIEW} exists for
 * completeness, but the Dashboard item is intentionally not gated
 * (no {@link SidebarSectionKey} value maps to it) so every tenant
 * keeps at least one landing entry point regardless of what the
 * admin has disabled.
 */
@Getter
@RequiredArgsConstructor
public enum SidebarGroup {
    OVERVIEW  ("Overview"),
    BILLING   ("Billing"),
    POLICIES  ("Policies"),
    MEMBERS   ("Members"),
    CLAIMS    ("Claims"),
    FINANCE   ("Finance"),
    REPORTING ("Reporting");

    private final String label;
}
