package com.medfund.contributions.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Request body for {@code POST /api/v1/contributions/billing/enqueue} — the
 * wizard's background-billing trigger. {@code kind} switches between a
 * read-only preview job and a persisting commit job. Filters mirror
 * {@link PreviewBillingRequest} / {@link CommitBillingRequest}.
 */
public record EnqueueBillingRequest(
        @NotNull @Pattern(regexp = "preview|commit",
            message = "kind must be 'preview' or 'commit'") String kind,
        @NotNull LocalDate periodStart,
        @NotNull LocalDate periodEnd,
        List<UUID> groupIds,
        List<UUID> memberIds
) {}
