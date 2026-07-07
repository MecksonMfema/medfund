package com.medfund.tenancy.dto;

import java.util.UUID;

/**
 * Snapshot of the tenant's member-number issuance config (V120 scheme
 * + V126 shape). The Angular super-admin panel binds directly to this
 * shape for editing.
 */
public record MemberNumberConfigResponse(
        UUID tenantId,
        String memberNumberScheme,
        String memberNumberPrefix,
        String dependantNumberPrefix,
        int memberNumberRandomLength,
        String memberNumberSuffixSeparator,
        int memberNumberSuffixPadding,
        int memberNumberSuffixStart
) {
    /** Byte-for-byte defaults matching V126 column defaults. */
    public static MemberNumberConfigResponse defaults(UUID tenantId) {
        return new MemberNumberConfigResponse(tenantId,
                "INDEPENDENT", "MBR-", "DEP-", 6, "-", 2, 1);
    }
}
