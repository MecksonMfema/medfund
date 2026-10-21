package com.medfund.finance.producer.dto;

import java.util.UUID;

/**
 * Per-member outcome of a {@link BulkReassignRequest}. {@code success=false}
 * carries {@code reason} — a short message extracted from the underlying
 * assignment error (missing producer, backdated etc). Never null.
 */
public record BulkReassignItem(
        UUID memberId,
        boolean success,
        String reason
) {}
