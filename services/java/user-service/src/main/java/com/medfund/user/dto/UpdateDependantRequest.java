package com.medfund.user.dto;

import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Partial update of an existing dependant. All fields are optional —
 * null means "no change". Used by the inline dependants editor on the
 * Member detail page.
 */
public record UpdateDependantRequest(
    @Size(max = 100) String firstName,
    @Size(max = 100) String lastName,
    LocalDate dateOfBirth,
    @Size(max = 10) String gender,
    @Size(max = 50) String relationship,
    @Size(max = 50) String nationalId
) {}
