package com.medfund.tenancy.dto;

import jakarta.validation.constraints.Size;

/**
 * Optional reason body for the public unsubscribe endpoint. Recipients may
 * be prompted for a free-form reason before confirming; it lands in the
 * audit event details for compliance context.
 */
public record UnsubscribeRequest(
        @Size(max = 500) String reason
) {}
