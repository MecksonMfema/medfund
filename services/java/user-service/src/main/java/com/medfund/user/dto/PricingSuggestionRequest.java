package com.medfund.user.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Enrolment-time AI pricing suggestion input. Represents everything the
 * (future) AI model would need to score a member or dependant: the
 * scheme they're joining, their date of birth, and a small set of risk
 * signals.
 *
 * <p>Currently backed by {@code PricingSuggestionService}'s stub — the
 * production model will swap in without changing this contract. Only
 * {@link #schemeId} and {@link #dateOfBirth} are required; the risk
 * signals are optional so an operator with partial info can still
 * request a suggestion.
 */
public record PricingSuggestionRequest(
    @NotNull
    UUID schemeId,

    @NotNull
    LocalDate dateOfBirth,

    /** Optional signal — the stub adjusts slightly by gender. */
    @Pattern(regexp = "^(male|female|other)?$")
    @Size(max = 10)
    String gender,

    /** True if the applicant has any tenant-tracked chronic condition. */
    Boolean hasChronicConditions,

    /** True if the applicant smokes. */
    Boolean smoker,

    /** Body-mass index — the stub only cares about {@code > 30}. */
    @DecimalMin(value = "0.0", message = "bmi must be non-negative")
    BigDecimal bmi
) {}
