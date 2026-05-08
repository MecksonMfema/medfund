package com.medfund.user.dto;

import java.util.List;

/**
 * Drives the role-editor UI's permission grid. The shape mirrors
 * {@code permissions.yaml} — domains in display order, each with its
 * permissions in display order. The frontend renders one accordion per domain.
 */
public record PermissionCatalogueResponse(List<Domain> domains) {

    public record Domain(String id, String label, List<Permission> permissions) {}

    public record Permission(String key, String label, String description) {}
}
