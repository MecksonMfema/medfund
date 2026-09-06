package com.medfund.tenancy.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Bulk-upsert payload for the tenant-admin Sidebar Visibility
 * settings tab. The admin flips a grid of toggles then hits Save
 * — one round-trip writes the whole set.
 *
 * <p>Rows the admin didn't touch stay untouched (server side does
 * an upsert per row, not a truncate-and-reinsert) — the payload
 * lists only the rows the admin explicitly enabled or disabled.
 */
public record UpdateTenantSidebarSectionConfigRequest(
        @NotNull @Valid @Size(min = 1, max = 200) List<ToggleEntry> entries) {

    public record ToggleEntry(
            @NotBlank @Size(max = 80) String sectionKey,
            @NotNull Boolean enabled
    ) {}
}
