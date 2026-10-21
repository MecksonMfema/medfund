package com.medfund.finance.producer.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/**
 * Payload for producer termination. {@code effectiveDate} snaps to
 * last-day-of-month server-side per {@code feedback_effective_date_snap}.
 */
public record TerminateProducerRequest(
        @NotNull LocalDate effectiveDate
) {}
