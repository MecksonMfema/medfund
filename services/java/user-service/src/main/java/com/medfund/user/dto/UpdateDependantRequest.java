package com.medfund.user.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Partial update of an existing dependant. All fields are optional —
 * null means "no change". Used by the inline dependants editor on the
 * Member detail page. Non-null values are still subject to format checks
 * (e.g. {@code gender} must be one of the canonical values), but a null
 * gender or nationalId on an update is fine and leaves the column alone.
 */
public record UpdateDependantRequest(
    @Size(max = 100) String firstName,
    @Size(max = 100) String lastName,
    LocalDate dateOfBirth,
    @Pattern(regexp = "^(male|female|other)$",
        message = "gender must be male, female, or other")
    @Size(max = 10) String gender,
    @Size(max = 50) String relationship,
    @Size(max = 50) String nationalId
) {}
