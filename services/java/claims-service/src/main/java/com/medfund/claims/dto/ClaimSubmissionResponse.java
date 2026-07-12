package com.medfund.claims.dto;

/**
 * Envelope returned from {@code POST /api/v1/claims}. Bundles the
 * created claim with the batch number the operator supplied
 * (nullable — batching is opt-in, and a claim submitted without one
 * echoes null here too).
 *
 * <p>Verification metadata used to live here (code + 5-minute window)
 * but was removed on 2026-07-11: operator-captured claims land VERIFIED
 * on submit and never need the out-of-band code exchange. When the
 * provider portal ships, provider-captured claims will get their own
 * envelope with verification back on it.
 */
public record ClaimSubmissionResponse(
        ClaimResponse claim,
        String batchNumber
) {}
