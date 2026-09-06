package com.medfund.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Body for {@code POST /api/v1/underwriting/cohorts/{id}/status-history/auto-transition}
 * — Phase 15 §15 (I11) service-to-service callback from the ai-service
 * onerous-test compute. Not a tenant-admin surface; a service-scoped
 * JWT is expected.
 *
 * <p>Idempotency is scoped to {@code (cohortId, sourceRunId)}: repeat
 * calls for the same report_job run return the original status-history
 * row without inserting a duplicate movement or emitting a second
 * material event. This matches the retry semantics of the outbox
 * publisher that fires the callback.
 */
public record AutoTransitionRequest(
        @Schema(description = "Cohort's cohort_type BEFORE the transition - used to build the "
                              + "status-history row's fromStatus column",
                example = "NON_ONEROUS")
        @NotBlank
        @Pattern(regexp = "ONEROUS|NON_ONEROUS|UNCERTAIN")
        String fromStatus,

        @Schema(description = "Cohort's cohort_type AFTER the transition - written back onto "
                              + "ifrs17_cohort.cohort_type and into the status-history row's "
                              + "toStatus column",
                example = "ONEROUS")
        @NotBlank
        @Pattern(regexp = "ONEROUS|NON_ONEROUS|UNCERTAIN")
        String toStatus,

        @Schema(description = "Onerous-test outcome discriminator - AUTO_TEST_FAILED on fresh "
                              + "onerous transitions, AUTO_TEST_RECOVERED on loss-component reversals",
                example = "AUTO_TEST_FAILED")
        @NotBlank
        @Pattern(regexp = "AUTO_TEST_FAILED|AUTO_TEST_RECOVERED")
        String transitionReason,

        @Schema(description = "report_job.id that triggered the transition - the idempotency key",
                example = "01930c26-b0a7-7c8e-b5ea-4a0e5f7bc123")
        @NotNull
        UUID sourceRunId,

        @Schema(description = "Positive loss-component amount to post as INITIAL_RECOGNITION when "
                              + "transitionReason=AUTO_TEST_FAILED; null on RECOVERED (matview "
                              + "reclassification does not require a per-movement amount)",
                example = "20.00")
        @DecimalMin(value = "0.01", message = "amount must be positive when supplied")
        BigDecimal lossComponentAmount,

        @Schema(description = "ISO-4217 code - required when lossComponentAmount is supplied",
                example = "USD")
        @Pattern(regexp = "[A-Z]{3}", message = "currency must be a 3-letter ISO-4217 code")
        String currency,

        @Schema(description = "Optional operator note carried onto audit + material events")
        @Size(max = 2000)
        String reasonNote
) {
}
